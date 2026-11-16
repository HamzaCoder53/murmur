import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Skein for Windows and macOS (Compose Multiplatform for Desktop).
//
// The mesh, Noise, protocol, Nostr, linked-device and backup cores are compiled from :app
// sources in place, exactly like :wear does: a Sync task materializes a filtered mirror into
// build/sharedSrc and that directory is added as a source root. Never copy shared Kotlin into
// desktop/src; extend the include list below instead. See docs/desktop-architecture.md for the
// reason behind every exclusion and for the android.* shim inventory (src/androidshim).

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

val sharedSourceIncludes = listOf(
    // Wire format, Noise, signing keys and identity persistence.
    "com/bitchat/android/protocol/**",
    "com/bitchat/android/noise/**",
    "com/bitchat/android/crypto/**",
    "com/bitchat/android/identity/**",
    "com/bitchat/android/model/**",
    // Transport-agnostic mesh core: routing, fragmentation, store-and-forward, gossip sync.
    "com/bitchat/android/mesh/AnnouncementIdentityValidator.kt",
    "com/bitchat/android/mesh/AuthenticatedPeerStateCoordinator.kt",
    "com/bitchat/android/mesh/BLEPacketPaddingPolicy.kt",
    "com/bitchat/android/mesh/DirectLinkAnnouncementPolicy.kt",
    "com/bitchat/android/mesh/FragmentManager.kt",
    "com/bitchat/android/mesh/FragmentingPacketSender.kt",
    "com/bitchat/android/mesh/MeshConnectionTracker.kt",
    "com/bitchat/android/mesh/MeshCore.kt",
    "com/bitchat/android/mesh/MeshDelegate.kt",
    "com/bitchat/android/mesh/MeshPacketUtils.kt",
    "com/bitchat/android/mesh/MeshService.kt",
    "com/bitchat/android/mesh/MeshTransport.kt",
    "com/bitchat/android/mesh/MessageHandler.kt",
    "com/bitchat/android/mesh/PacketProcessor.kt",
    "com/bitchat/android/mesh/PacketRelayManager.kt",
    "com/bitchat/android/mesh/PeerFingerprintManager.kt",
    "com/bitchat/android/mesh/PeerManager.kt",
    "com/bitchat/android/mesh/PrivateMediaSecurity.kt",
    "com/bitchat/android/mesh/PrivateMediaTransfer.kt",
    "com/bitchat/android/mesh/RetryingControlPacketSender.kt",
    "com/bitchat/android/mesh/SecurityManager.kt",
    "com/bitchat/android/mesh/StoreForwardManager.kt",
    "com/bitchat/android/mesh/TransferProgressManager.kt",
    "com/bitchat/android/sync/**",
    "com/bitchat/android/service/TransportBridgeService.kt",
    "com/bitchat/android/favorites/**",
    "com/bitchat/android/spam/**",
    "com/bitchat/android/services/SeenMessageStore.kt",
    "com/bitchat/android/services/ContactDirectory.kt",
    "com/bitchat/android/services/ContactIdentityResolver.kt",
    "com/bitchat/android/services/PeerConversationReconciler.kt",
    "com/bitchat/android/services/PrivateMessageArrivalOrder.kt",
    "com/bitchat/android/services/VerificationService.kt",
    "com/bitchat/android/services/meshgraph/**",
    "com/bitchat/android/ui/debug/DebugSettingsManager.kt",
    "com/bitchat/android/ui/debug/DebugPreferenceManager.kt",
    "com/bitchat/android/util/AppConstants.kt",
    "com/bitchat/android/util/ByteArrayExtensions.kt",
    "com/bitchat/android/util/ByteArrayWrapper.kt",
    "com/bitchat/android/util/BinaryEncodingUtils.kt",
    "com/bitchat/android/features/voice/VoiceBurstPacket.kt",
    // Nostr: event model, NIP-17/44 gift-wrap crypto, filters/requests, proof of work, dedup and
    // reconnect policy. The websocket relay client (NostrRelayManager) is not mirrored yet: it is
    // wired to the phone's PowerManager, Tor OkHttpProvider and RelayDirectory (see the doc).
    "com/bitchat/android/nostr/Bech32.kt",
    "com/bitchat/android/nostr/NostrCrypto.kt",
    "com/bitchat/android/nostr/NostrEvent.kt",
    "com/bitchat/android/nostr/NostrFilter.kt",
    "com/bitchat/android/nostr/NostrRequest.kt",
    "com/bitchat/android/nostr/NostrProtocol.kt",
    "com/bitchat/android/nostr/NostrProofOfWork.kt",
    "com/bitchat/android/nostr/PoWPreferenceManager.kt",
    "com/bitchat/android/nostr/NostrEmbeddedBitChat.kt",
    "com/bitchat/android/nostr/NostrIdentity.kt",
    "com/bitchat/android/nostr/NostrEventDeduplicator.kt",
    "com/bitchat/android/nostr/NostrLiveSubscriptionPrivacy.kt",
    "com/bitchat/android/nostr/NostrPendingEventQueue.kt",
    "com/bitchat/android/nostr/RelayReconnectPolicy.kt",
    "com/bitchat/android/nostr/VerifiedEventFilter.kt",
    "com/bitchat/android/nostr/GeohashAliasRegistry.kt",
    "com/bitchat/android/geohash/Geohash.kt",
    "com/bitchat/android/geohash/LocationChannel.kt",
    // Linked devices (shared identity) and code pairing.
    "com/bitchat/android/linked/LinkCoordinator.kt",
    "com/bitchat/android/linked/LinkCrypto.kt",
    "com/bitchat/android/linked/LinkHandshake.kt",
    "com/bitchat/android/linked/LinkLogic.kt",
    "com/bitchat/android/linked/LinkProtocol.kt",
    "com/bitchat/android/linked/LinkQr.kt",
    "com/bitchat/android/linked/LinkSession.kt",
    "com/bitchat/android/linked/LinkStore.kt",
    "com/bitchat/android/linked/LinkedAppBridge.kt",
    "com/bitchat/android/linked/PasskeyPairing.kt",
    // Backup archive format and phone-move transfer protocol.
    "com/bitchat/android/backup/BackupChats.kt",
    "com/bitchat/android/backup/BackupImporter.kt",
    "com/bitchat/android/backup/SkeinArchive.kt",
    "com/bitchat/android/backup/SkeinCrypto.kt",
    "com/bitchat/android/backup/TransferProtocol.kt",
    "com/bitchat/android/wearlink/IdentityBundle.kt",
)
val sharedSourceExcludes = listOf(
    // Uses android.net.Uri + ContentResolver to read picked files; desktop gets its own picker.
    "com/bitchat/android/model/FileSharingManager.kt",
)
// Desktop variants with the same FQN live in src/main/kotlin/com/bitchat/android (like the
// watch's BluetoothPermissionManager): AppStateStore, MessageHistory, FileUtils,
// LiveVoiceManager, MeshServiceHolder, WifiAwareController. Never include their app originals.

val syncSharedAppSources = tasks.register<Sync>("syncSharedAppSources") {
    from("../app/src/main/java") {
        include(sharedSourceIncludes)
        exclude(sharedSourceExcludes)
    }
    into(layout.buildDirectory.dir("sharedSrc"))
}

// The app's own pure-JVM unit tests for the mirrored packages also run here, so golden vectors
// and protocol behaviour are verified on the desktop JVM too. Tests that need Robolectric's
// Android runtime (Context, Keystore, shadows) stay phone-only; tests that only use its runner
// (for Android's org.json) run under the plain-JUnit RobolectricTestRunner in src/test.
val syncSharedAppTests = tasks.register<Sync>("syncSharedAppTests") {
    from("../app/src/test/java") {
        include(
            "com/bitchat/android/protocol/**",
            "com/bitchat/android/linked/PasskeyPairingTest.kt",
            "com/bitchat/android/linked/LinkHandshakeAndFrameTest.kt",
            "com/bitchat/android/linked/LinkCoordinatorTest.kt",
            "com/bitchat/android/mesh/BLEPacketPaddingPolicyTest.kt",
            "com/bitchat/android/mesh/FragmentManagerTest.kt",
            "com/bitchat/android/mesh/FragmentingPacketSenderTest.kt",
            "com/bitchat/android/favorites/FavoriteRelationshipTest.kt",
            "com/bitchat/android/nostr/Bech32PaddingTest.kt",
            "com/bitchat/android/nostr/NostrLiveSubscriptionPrivacyTest.kt",
            "com/bitchat/android/spam/SpamFilterTest.kt",
        )
    }
    from("../app/src/test/kotlin") {
        include(
            "com/bitchat/android/contracts/**",
            "com/bitchat/android/noise/**",
            "com/bitchat/android/backup/SkeinBackupTest.kt",
            "com/bitchat/android/sync/GCSFilterTest.kt",
            "com/bitchat/android/sync/GCSFilterParameterTest.kt",
            "com/bitchat/android/sync/GossipSyncLoadTest.kt",
            "com/bitchat/android/mesh/DirectLinkAnnouncementPolicyTest.kt",
            "com/bitchat/android/mesh/RetryingControlPacketSenderTest.kt",
            "com/bitchat/android/mesh/AuthenticatedPeerStateCoordinatorTest.kt",
            "com/bitchat/android/mesh/PacketProcessorAnnounceSideEffectTest.kt",
            "com/bitchat/android/mesh/PrivateMediaSecurityTest.kt",
            "com/bitchat/android/model/AuthenticatedPeerStateTest.kt",
            "com/bitchat/android/model/IdentityAnnouncementTest.kt",
            "com/bitchat/android/nostr/NostrEventDeduplicatorTest.kt",
            "com/bitchat/android/nostr/NostrPendingEventQueueTest.kt",
            "com/bitchat/android/nostr/NostrProtocolTest.kt",
            "com/bitchat/android/nostr/RelayReconnectPolicyTest.kt",
            "com/bitchat/android/nostr/VerifiedEventFilterTest.kt",
            "com/bitchat/android/services/meshgraph/MeshGraphServiceTest.kt",
            "com/bitchat/android/services/PeerConversationReconcilerTest.kt",
        )
    }
    into(layout.buildDirectory.dir("sharedTestSrc"))
}

sourceSets {
    main {
        java.srcDir(syncSharedAppSources)
        kotlin.srcDir(syncSharedAppSources)
        // JVM implementations of the small android.* / androidx.* subset the shared core uses.
        kotlin.srcDir("src/androidshim/kotlin")
        java.srcDir("src/androidshim/java")
    }
    test {
        kotlin.srcDir(syncSharedAppTests)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

composeCompiler {
    includeComposeMappingFile.set(false)
}

dependencies {
    // Compose for Desktop. Every supported OS/arch runtime is declared explicitly (instead of
    // compose.desktop.currentOs) so that the strict lockfile and checksum metadata are the same
    // on every build host; packaging passes -PskeinTarget=<macos_arm64|macos_x64|windows_x64|windows_arm64>
    // to bundle only the target's native runtime.
    implementation(compose.desktop.common)
    implementation(compose.material3)
    val skeinTarget = providers.gradleProperty("skeinTarget").orNull
    if (skeinTarget == null || skeinTarget == "macos_arm64") runtimeOnly(compose.desktop.macos_arm64)
    if (skeinTarget == null || skeinTarget == "macos_x64") runtimeOnly(compose.desktop.macos_x64)
    if (skeinTarget == null || skeinTarget == "windows_x64") runtimeOnly(compose.desktop.windows_x64)
    if (skeinTarget == null || skeinTarget == "windows_arm64") runtimeOnly(compose.desktop.windows_arm64)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.google.tink)
    // Android ships org.json in the platform; the backup archive format uses it.
    implementation(libs.org.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // Never touch the real per-user app-data directory from tests.
    systemProperty("skein.dataDir", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "com.bitchat.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Skein"
            packageVersion = providers.gradleProperty("skeinDesktopVersion").getOrElse("1.0.0")
            macOS {
                bundleID = "xfuturehb.murmur.desktop"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSBluetoothAlwaysUsageDescription</key>
                        <string>Skein uses Bluetooth Low Energy to discover and message nearby peers without internet.</string>
                    """.trimIndent()
                }
            }
            windows {
                upgradeUuid = "b9e2c4a1-7d3f-4c8b-9a2e-1f6d8c3e5a7b"
                menuGroup = "Skein"
            }
        }
    }
}

tasks.matching { it.name.startsWith("package") }.configureEach {
    doFirst {
        check(System.getProperty("skein.allowInsecureKeyStore")?.toBoolean() != true) {
            "Refusing to package Skein with -Dskein.allowInsecureKeyStore=true"
        }
    }
}

// `./gradlew :desktop:run --args=--smoke` uses the run task the Compose plugin registers.
