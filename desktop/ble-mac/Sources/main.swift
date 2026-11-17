import CoreBluetooth
import Foundation

// Bitchat / Skein BLE service and characteristic UUIDs (must match AppConstants.kt)
private let serviceUUID = CBUUID(string: "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
private let characteristicUUID = CBUUID(string: "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")

private let bleQueue = DispatchQueue(label: "xfuturehb.murmur.desktop.ble", qos: .userInitiated)
private let stdoutLock = NSLock()

private func emit(_ dict: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: dict, options: []),
          let line = String(data: data, encoding: .utf8) else { return }
    stdoutLock.lock()
    FileHandle.standardOutput.write(Data((line + "\n").utf8))
    stdoutLock.unlock()
}

final class BleHelper: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, CBPeripheralManagerDelegate {
    private var central: CBCentralManager?
    private var peripheral: CBPeripheralManager?
    private var gattChar: CBMutableCharacteristic?
    private var isStarted = false

    // Central links: opaque linkId <-> CBPeripheral + characteristic
    private var centralLinksById: [String: (peripheral: CBPeripheral, char: CBCharacteristic?)] = [:]
    private var centralIdByPeripheral: [UUID: String] = [:]

    // Peripheral links: opaque linkId <-> CBCentral
    private var peripheralLinksById: [String: CBCentral] = [:]
    private var peripheralIdByCentral: [UUID: String] = [:]

    // Backpressure: frames CoreBluetooth could not take yet, retried when it says it is ready.
    // Bounded so a stalled link cannot grow memory without limit.
    private let maxQueuedFrames = 256
    private var pendingWrites: [String: [Data]] = [:]   // central role, per link
    private var pendingNotifies: [(linkId: String, data: Data)] = []   // peripheral role

    // Broadcast power (Settings). CoreBluetooth has no TX-power or advertising-interval API, so,
    // like the phone's PowerManager, the level sets how weak a peer may be before we connect to
    // it and how many outgoing (central) links we keep. Guarded by bleQueue.
    private var rssiThreshold = -85
    private var maxCentralLinks = 6

    func setPower(_ level: String) {
        bleQueue.async { [weak self] in
            guard let self = self else { return }
            switch level {
            case "low": self.rssiThreshold = -75; self.maxCentralLinks = 4
            case "high": self.rssiThreshold = -95; self.maxCentralLinks = 8
            default: self.rssiThreshold = -85; self.maxCentralLinks = 6
            }
        }
    }

    func start() {
        bleQueue.async { [weak self] in
            guard let self = self, !self.isStarted else { return }
            self.isStarted = true
            self.central = CBCentralManager(delegate: self, queue: bleQueue)
            self.peripheral = CBPeripheralManager(delegate: self, queue: bleQueue)
        }
    }

    func stop() {
        bleQueue.async { [weak self] in
            guard let self = self, self.isStarted else { return }
            self.isStarted = false
            self.central?.stopScan()
            for (_, entry) in self.centralLinksById {
                self.central?.cancelPeripheralConnection(entry.peripheral)
            }
            self.centralLinksById.removeAll()
            self.centralIdByPeripheral.removeAll()
            self.peripheral?.stopAdvertising()
            self.peripheral?.removeAllServices()
            self.peripheralLinksById.removeAll()
            self.peripheralIdByCentral.removeAll()
            self.pendingWrites.removeAll()
            self.pendingNotifies.removeAll()
        }
    }

    func send(linkId: String, data: Data) {
        bleQueue.async { [weak self] in
            guard let self = self else { return }
            if self.centralLinksById[linkId] != nil {
                self.pendingWrites[linkId, default: []].append(data)
                if self.pendingWrites[linkId]!.count > self.maxQueuedFrames {
                    self.pendingWrites[linkId]!.removeFirst()
                    emit(["ev": "error", "message": "BLE write queue full; dropped oldest frame"])
                }
                self.drainWrites(linkId: linkId)
                return
            }
            if self.peripheralLinksById[linkId] != nil {
                self.pendingNotifies.append((linkId, data))
                if self.pendingNotifies.count > self.maxQueuedFrames {
                    self.pendingNotifies.removeFirst()
                    emit(["ev": "error", "message": "BLE notify queue full; dropped oldest frame"])
                }
                self.drainNotifies()
            }
        }
    }

    /// Central role. Uses write-without-response when the frame fits the negotiated length and
    /// CoreBluetooth has room; otherwise write-with-response (which CoreBluetooth splits into a
    /// long write up to 512 bytes). Stops when the queue is full and resumes on readiness.
    private func drainWrites(linkId: String) {
        guard let entry = centralLinksById[linkId], let char = entry.char else { return }
        let p = entry.peripheral
        while let data = pendingWrites[linkId]?.first {
            let noRspMax = p.maximumWriteValueLength(for: .withoutResponse)
            let rspMax = p.maximumWriteValueLength(for: .withResponse)
            if char.properties.contains(.writeWithoutResponse) && data.count <= noRspMax {
                guard p.canSendWriteWithoutResponse else { return } // resumes in peripheralIsReady
                p.writeValue(data, for: char, type: .withoutResponse)
            } else if char.properties.contains(.write) && data.count <= rspMax {
                p.writeValue(data, for: char, type: .withResponse)
            } else {
                emit(["ev": "error", "message": "BLE frame of \(data.count) bytes exceeds link limit; dropped"])
            }
            pendingWrites[linkId]?.removeFirst()
        }
    }

    /// Peripheral role. updateValue returns false when the transmit queue is full; the frame
    /// stays queued and is retried from peripheralManagerIsReady(toUpdateSubscribers:).
    private func drainNotifies() {
        guard let char = gattChar, let pm = peripheral else { return }
        while let next = pendingNotifies.first {
            guard let subscriber = peripheralLinksById[next.linkId] else {
                pendingNotifies.removeFirst()
                continue
            }
            if next.data.count > subscriber.maximumUpdateValueLength {
                emit(["ev": "error", "message": "BLE frame of \(next.data.count) bytes exceeds notify limit; dropped"])
                pendingNotifies.removeFirst()
                continue
            }
            guard pm.updateValue(next.data, for: char, onSubscribedCentrals: [subscriber]) else { return }
            pendingNotifies.removeFirst()
        }
    }

    // MARK: - State reporting

    private func reportState() {
        let cState = central?.state ?? .unknown
        let stateStr: String
        switch cState {
        case .poweredOn: stateStr = "ready"
        case .poweredOff: stateStr = "poweredOff"
        case .unauthorized: stateStr = "unauthorized"
        case .unsupported: stateStr = "unsupported"
        default: return
        }
        emit(["ev": "state", "state": stateStr])
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        reportState()
        if central.state == .poweredOn && isStarted {
            central.scanForPeripherals(withServices: [serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        if centralIdByPeripheral[peripheral.identifier] != nil { return }
        // 127 means CoreBluetooth has no RSSI for this advertisement: do not filter on it.
        let rssi = RSSI.intValue
        if rssi != 127 && rssi < rssiThreshold { return }
        if centralLinksById.count >= maxCentralLinks { return }
        let linkId = UUID().uuidString
        centralIdByPeripheral[peripheral.identifier] = linkId
        centralLinksById[linkId] = (peripheral, nil)
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        // linkUp is reported once the characteristic is found, so no send is lost before then.
        guard centralIdByPeripheral[peripheral.identifier] != nil else { return }
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard let linkId = centralIdByPeripheral.removeValue(forKey: peripheral.identifier) else { return }
        let wasUp = centralLinksById.removeValue(forKey: linkId)?.char != nil
        pendingWrites.removeValue(forKey: linkId)
        if wasUp { emit(["ev": "linkDown", "link": linkId]) }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if let linkId = centralIdByPeripheral.removeValue(forKey: peripheral.identifier) {
            centralLinksById.removeValue(forKey: linkId)
        }
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services else { return }
        for svc in services where svc.uuid == serviceUUID {
            peripheral.discoverCharacteristics([characteristicUUID], for: svc)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil, let chars = service.characteristics,
              let linkId = centralIdByPeripheral[peripheral.identifier] else { return }
        for ch in chars where ch.uuid == characteristicUUID {
            let alreadyUp = centralLinksById[linkId]?.char != nil
            centralLinksById[linkId] = (peripheral, ch)
            peripheral.setNotifyValue(true, for: ch)
            let mtu = peripheral.maximumWriteValueLength(for: .withoutResponse)
            emit(["ev": alreadyUp ? "linkUpdated" : "linkUp", "link": linkId, "mtu": mtu, "role": "central"])
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard let linkId = centralIdByPeripheral[peripheral.identifier] else { return }
        drainWrites(linkId: linkId)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let data = characteristic.value, !data.isEmpty,
              let linkId = centralIdByPeripheral[peripheral.identifier] else { return }
        emit(["ev": "frame", "link": linkId, "b64": data.base64EncodedString()])
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral.state == .poweredOn && isStarted else { return }
        let char = CBMutableCharacteristic(
            type: characteristicUUID,
            properties: [.read, .write, .writeWithoutResponse, .notify],
            value: nil,
            permissions: [.readable, .writeable]
        )
        gattChar = char
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [char]
        peripheral.add(service)
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceUUID]])
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        let linkId = peripheralIdByCentral[central.identifier] ?? {
            let id = UUID().uuidString
            peripheralIdByCentral[central.identifier] = id
            peripheralLinksById[id] = central
            emit(["ev": "linkUp", "link": id, "mtu": central.maximumUpdateValueLength, "role": "peripheral"])
            return id
        }()
        _ = linkId
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard let linkId = peripheralIdByCentral.removeValue(forKey: central.identifier) else { return }
        peripheralLinksById.removeValue(forKey: linkId)
        pendingNotifies.removeAll { $0.linkId == linkId }
        emit(["ev": "linkDown", "link": linkId])
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        drainNotifies()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for req in requests {
            if let data = req.value, !data.isEmpty {
                let linkId = peripheralIdByCentral[req.central.identifier] ?? {
                    let id = UUID().uuidString
                    peripheralIdByCentral[req.central.identifier] = id
                    peripheralLinksById[id] = req.central
                    emit(["ev": "linkUp", "link": id, "mtu": req.central.maximumUpdateValueLength, "role": "peripheral"])
                    return id
                }()
                emit(["ev": "frame", "link": linkId, "b64": data.base64EncodedString()])
            }
            peripheral.respond(to: req, withResult: .success)
        }
    }
}

let helper = BleHelper()
DispatchQueue.global(qos: .userInitiated).async {
    while let line = readLine(strippingNewline: true) {
        guard let data = line.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let op = obj["op"] as? String else { continue }
        switch op {
        case "start":
            helper.start()
        case "stop":
            helper.stop()
            exit(0)
        case "power":
            if let level = obj["level"] as? String { helper.setPower(level) }
        case "send":
            if let link = obj["link"] as? String,
               let b64 = obj["b64"] as? String,
               let payload = Data(base64Encoded: b64) {
                helper.send(linkId: link, data: payload)
            }
        default:
            break
        }
    }
    helper.stop()
    exit(0)
}
RunLoop.main.run()
