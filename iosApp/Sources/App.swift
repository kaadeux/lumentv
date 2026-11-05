import AVFoundation
import KSPlayer
import LumenKit
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
    /// The player turns sideways.
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Orientation.landscapeOnly ? .landscape : .allButUpsideDown
    }
}

/// The Kotlin app as a SwiftUI view.
struct ComposeHost: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainKt.MainViewController(player: LumenPlayer.shared) }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
