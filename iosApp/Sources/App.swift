import AVFoundation
import KSPlayer
import LumenKit
import Network
import SwiftUI

/// The iPhone app: the shared Kotlin app (LumenKit, Compose Multiplatform) full screen; KSPlayer plays.
@main
struct LumenTVApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var phase

    init() {
        // Sound keeps going in Picture in Picture and with the screen locked.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        KSOptions.canBackgroundPlay = true
        KSOptions.isAutoPlay = true
        // KSPlayer would set the ProMotion screen to the video's rate (25 to 50 Hz for a 25 fps film): iOS then picks
        // 30, 40 or 60 Hz and repeats one frame in five or so, a steady stutter. At 120 Hz every rate plays evenly.
        KSOptions.preferredFrame = false
        // Names looked up over HTTPS at Cloudflare, not at the internet provider's DNS (as on the TV and the PC):
        // every URLSession and Network connection of the app, AVPlayer's included.
        NWParameters.PrivacyContext.default.requireEncryptedNameResolution(true, fallbackResolver: .https(
            URL(string: "https://cloudflare-dns.com/dns-query")!,
            serverAddresses: ["1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"].map { NWEndpoint.hostPort(host: NWEndpoint.Host($0), port: 443) }))
        // TMDB's answers (lists, logos, ratings): 20 MB on disk at most, as on the TV and the PC; images have their own
        // 60 MB cache (Coil, shared app).
        URLCache.shared = URLCache(memoryCapacity: 8 * 1024 * 1024, diskCapacity: 20 * 1024 * 1024)
        // Downloads left running when the app was closed report back to the shared app.
        _ = LumenDownloader.shared.session
        LumenDownloader.shared.reportActive()
    }

    var body: some Scene {
        WindowGroup {
            ComposeHost().ignoresSafeArea().preferredColorScheme(.dark)
        }
        .onChange(of: phase) { newPhase in
            // The online profile syncs when the app comes back.
            if newPhase == .active { MainKt.appDidBecomeActive() }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// The app stays upright; only the player turns sideways.
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Orientation.landscapeOnly ? .landscape : .portrait
    }

    /// iOS wakes the app when a background download ends.
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        LumenDownloader.shared.backgroundCompletion = completionHandler
        _ = LumenDownloader.shared.session
    }
}

/// The Kotlin app as a SwiftUI view.
struct ComposeHost: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainKt.MainViewController(player: LumenPlayer.shared, downloader: LumenDownloader.shared) }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
