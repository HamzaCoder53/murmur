using System;
using System.Collections.Concurrent;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text.Json;
using System.Text.Json.Nodes;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Devices.Radios;
using Windows.Storage.Streams;

namespace SkeinBleWindows;

internal static class Program
{
    private static readonly Guid ServiceUuid = Guid.Parse("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C");
    private static readonly Guid CharacteristicUuid = Guid.Parse("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D");

    private static readonly object StdoutLock = new();
    private static readonly ConcurrentDictionary<string, CentralLink> CentralLinks = new();
    private static readonly ConcurrentDictionary<ulong, string> CentralIdByAddress = new();
    private static readonly ConcurrentDictionary<string, GattSubscribedClient> PeripheralLinks = new();
    private static readonly ConcurrentDictionary<string, string> PeripheralIdBySession = new();

    private static BluetoothLEAdvertisementWatcher? _watcher;
    private static GattServiceProvider? _serviceProvider;
    private static GattLocalCharacteristic? _localCharacteristic;
    private static Radio? _radio;
    private static volatile bool _running;

    private sealed record CentralLink(BluetoothLEDevice Device, GattCharacteristic Characteristic);

    public static async Task Main()
    {
        while (true)
        {
            var line = await Console.In.ReadLineAsync();
            if (line is null) break;
            if (string.IsNullOrWhiteSpace(line)) continue;

            try
            {
                var node = JsonNode.Parse(line)?.AsObject();
                var op = node?["op"]?.GetValue<string>();
                switch (op)
                {
                    case "start":
                        await StartAsync();
                        break;
                    case "stop":
                        Stop();
                        return;
                    case "send":
                        var link = node?["link"]?.GetValue<string>();
                        var b64 = node?["b64"]?.GetValue<string>();
                        if (!string.IsNullOrEmpty(link) && !string.IsNullOrEmpty(b64))
                        {
                            await SendAsync(link, Convert.FromBase64String(b64));
                        }
                        break;
                }
            }
            catch (Exception ex)
            {
                Emit(new { ev = "error", message = ex.Message });
            }
        }
        Stop();
    }

    private static async Task StartAsync()
    {
        if (_running) return;
        _running = true;

        var adapter = await BluetoothAdapter.GetDefaultAsync();
        if (adapter is null || !adapter.IsLowEnergySupported)
        {
            Emit(new { ev = "state", state = "unsupported" });
            return;
        }

        _radio = await adapter.GetRadioAsync();
        if (_radio is not null)
        {
            _radio.StateChanged += (_, _) => ReportRadioState(_radio.State);
            ReportRadioState(_radio.State);
        }
        else
        {
            Emit(new { ev = "state", state = "ready" });
        }

        _watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active
        };
        _watcher.AdvertisementFilter.Advertisement.ServiceUuids.Add(ServiceUuid);
        _watcher.Received += async (_, args) => await OnAdvertisementReceivedAsync(args);
        _watcher.Start();

        if (adapter.IsPeripheralRoleSupported)
        {
            await StartPeripheralAsync();
        }
    }

    private static void Stop()
    {
        _running = false;
        try { _watcher?.Stop(); } catch { }
        _watcher = null;

        try { _serviceProvider?.StopAdvertising(); } catch { }
        _serviceProvider = null;

        foreach (var kv in CentralLinks)
        {
            kv.Value.Device.Dispose();
        }
        CentralLinks.Clear();
        CentralIdByAddress.Clear();
        PeripheralLinks.Clear();
        PeripheralIdBySession.Clear();
    }

    private static void ReportRadioState(RadioState state)
    {
        var mapped = state switch
        {
            RadioState.On => "ready",
            RadioState.Off => "poweredOff",
            RadioState.Disabled => "unauthorized",
            _ => "unsupported"
        };
        Emit(new { ev = "state", state = mapped });
    }

    private static async Task OnAdvertisementReceivedAsync(BluetoothLEAdvertisementReceivedEventArgs args)
    {
        if (!_running || CentralIdByAddress.ContainsKey(args.BluetoothAddress)) return;
        var linkId = Guid.NewGuid().ToString("N");
        if (!CentralIdByAddress.TryAdd(args.BluetoothAddress, linkId)) return;

        try
        {
            var device = await BluetoothLEDevice.FromBluetoothAddressAsync(args.BluetoothAddress);
            if (device is null)
            {
                CentralIdByAddress.TryRemove(args.BluetoothAddress, out _);
                return;
            }

            var svcResult = await device.GetGattServicesForUuidAsync(ServiceUuid, BluetoothCacheMode.Uncached);
            if (svcResult.Status != GattCommunicationStatus.Success || svcResult.Services.Count == 0)
            {
                device.Dispose();
                CentralIdByAddress.TryRemove(args.BluetoothAddress, out _);
                return;
            }

            var service = svcResult.Services[0];
            var charResult = await service.GetCharacteristicsForUuidAsync(CharacteristicUuid, BluetoothCacheMode.Uncached);
            if (charResult.Status != GattCommunicationStatus.Success || charResult.Characteristics.Count == 0)
            {
                device.Dispose();
                CentralIdByAddress.TryRemove(args.BluetoothAddress, out _);
                return;
            }

            var ch = charResult.Characteristics[0];
            CentralLinks[linkId] = new CentralLink(device, ch);
            device.ConnectionStatusChanged += (d, _) =>
            {
                if (d.ConnectionStatus == BluetoothConnectionStatus.Disconnected)
                {
                    if (CentralIdByAddress.TryRemove(d.BluetoothAddress, out var id))
                    {
                        CentralLinks.TryRemove(id, out _);
                        Emit(new { ev = "linkDown", link = id });
                    }
                    d.Dispose();
                }
            };

            ch.ValueChanged += (_, valArgs) =>
            {
                var bytes = valArgs.CharacteristicValue.ToArray();
                if (bytes.Length > 0)
                {
                    Emit(new { ev = "frame", link = linkId, b64 = Convert.ToBase64String(bytes) });
                }
            };
            await ch.WriteClientCharacteristicConfigurationDescriptorAsync(
                GattClientCharacteristicConfigurationDescriptorValue.Notify);

            var mtu = (int)Math.Max(23, service.Session.MaxPduSize - 3);
            Emit(new { ev = "linkUp", link = linkId, rssi = (int)args.RawSignalStrengthInDBm, mtu, role = "central" });
        }
        catch (Exception ex)
        {
            CentralIdByAddress.TryRemove(args.BluetoothAddress, out _);
            CentralLinks.TryRemove(linkId, out _);
            Emit(new { ev = "error", message = ex.Message });
        }
    }

    private static async Task StartPeripheralAsync()
    {
        var result = await GattServiceProvider.CreateAsync(ServiceUuid);
        if (result.Error != BluetoothError.Success) return;

        _serviceProvider = result.ServiceProvider;
        var parameters = new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = GattCharacteristicProperties.Read
                | GattCharacteristicProperties.Write
                | GattCharacteristicProperties.WriteWithoutResponse
                | GattCharacteristicProperties.Notify,
            WriteProtectionLevel = GattProtectionLevel.Plain,
            ReadProtectionLevel = GattProtectionLevel.Plain
        };

        var charResult = await _serviceProvider.Service.CreateCharacteristicAsync(CharacteristicUuid, parameters);
        if (charResult.Error != BluetoothError.Success) return;

        _localCharacteristic = charResult.Characteristic;
        _localCharacteristic.SubscribedClientsChanged += (sender, _) =>
        {
            var activeSessions = new HashSet<string>();
            foreach (var client in sender.SubscribedClients)
            {
                var sessionKey = client.Session.DeviceId.Id;
                activeSessions.Add(sessionKey);
                var linkId = PeripheralIdBySession.GetOrAdd(sessionKey, _ =>
                {
                    var id = Guid.NewGuid().ToString("N");
                    var mtu = (int)Math.Max(23, client.Session.MaxPduSize - 3);
                    Emit(new { ev = "linkUp", link = id, mtu, role = "peripheral" });
                    return id;
                });
                PeripheralLinks[linkId] = client;
            }
            foreach (var kv in PeripheralIdBySession)
            {
                if (!activeSessions.Contains(kv.Key) && PeripheralIdBySession.TryRemove(kv.Key, out var removedId))
                {
                    PeripheralLinks.TryRemove(removedId, out _);
                    Emit(new { ev = "linkDown", link = removedId });
                }
            }
        };

        _localCharacteristic.WriteRequested += async (_, args) =>
        {
            var deferral = args.GetDeferral();
            try
            {
                var req = await args.GetRequestAsync();
                var bytes = req.Value.ToArray();
                if (bytes.Length > 0)
                {
                    var sessionKey = args.Session.DeviceId.Id;
                    var linkId = PeripheralIdBySession.GetOrAdd(sessionKey, _ =>
                    {
                        var id = Guid.NewGuid().ToString("N");
                        var mtu = (int)Math.Max(23, args.Session.MaxPduSize - 3);
                        Emit(new { ev = "linkUp", link = id, mtu, role = "peripheral" });
                        return id;
                    });
                    Emit(new { ev = "frame", link = linkId, b64 = Convert.ToBase64String(bytes) });
                }
                if (req.Option == GattWriteOption.WriteWithResponse)
                {
                    req.Respond();
                }
            }
            finally
            {
                deferral.Complete();
            }
        };

        _serviceProvider.StartAdvertising(new GattServiceProviderAdvertisingParameters
        {
            IsDiscoverable = true,
            IsConnectable = true
        });
    }

    private static async Task SendAsync(string linkId, byte[] payload)
    {
        var buffer = payload.AsBuffer();
        if (CentralLinks.TryGetValue(linkId, out var central))
        {
            var option = central.Characteristic.CharacteristicProperties.HasFlag(GattCharacteristicProperties.WriteWithoutResponse)
                ? GattWriteOption.WriteWithoutResponse
                : GattWriteOption.WriteWithResponse;
            await central.Characteristic.WriteValueAsync(buffer, option);
            return;
        }
        if (PeripheralLinks.TryGetValue(linkId, out var client) && _localCharacteristic is not null)
        {
            await _localCharacteristic.NotifyValueAsync(buffer, client);
        }
    }

    private static void Emit(object payload)
    {
        var json = JsonSerializer.Serialize(payload);
        lock (StdoutLock)
        {
            Console.Out.WriteLine(json);
            Console.Out.Flush();
        }
    }
}
