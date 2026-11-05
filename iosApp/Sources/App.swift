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
        // KSPlayer would set the ProMotion screen to the video's rate (25 to 50 Hz for a 25 fps film): iOS then picks
        // 30, 40 or 60 Hz and repeats one frame in five or so, a steady stutter. At 120 Hz every rate plays evenly.
        KSOptions.preferredFrame = false
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
