import AVFoundation
import KSPlayer
import SwiftUI

/// Phase 0 test app: Xtream sign-in, search, playback with KSPlayer (AVPlayer or FFmpeg engine).
@main
struct LumenPOCApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var store = Store()

    init() {
        // Sound keeps going in Picture in Picture and with the screen locked.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        KSOptions.canBackgroundPlay = true
        KSOptions.isAutoPlay = true
    }

    var body: some Scene {
        WindowGroup { ContentView().environmentObject(store).preferredColorScheme(.dark) }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// KSPlayer's full-screen view turns sideways.
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask { .allButUpsideDown }
}
