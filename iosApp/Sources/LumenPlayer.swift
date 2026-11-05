import KSPlayer
import LumenKit
import SwiftUI
import UIKit

/// The shared app's player on the iPhone (VideoPlayer in :phoneui): KSPlayer full screen over the app.
final class LumenPlayer: NSObject, VideoPlayer {
    static let shared = LumenPlayer()

    func play(request: PlayRequest) {
        DispatchQueue.main.async {
            // A downloaded file is named by the shared app; its folder is found here (it changes at each update).
            let prefix = "lumen-download:"
            let url = request.url.hasPrefix(prefix) ? LumenDownloader.file(String(request.url.dropFirst(prefix.count))) : URL(string: request.url)
            guard let url, let top = Self.topController() else { return }
            let box = HostBox()
            let screen = PlayerScreen(request: request, url: url) { position, duration, step in
                // The position is kept first; then the episode before / after opens in a new player.
                request.finish(positionMs: position, durationMs: duration)
                Orientation.landscape(false)
                box.controller?.dismiss(animated: step == .none) {
                    switch step {
                    case .previous: request.previous()
                    case .next: request.next()
                    case .none: break
                    }
                }
            }
            let controller = LandscapeHost(rootView: screen)
            controller.modalPresentationStyle = .fullScreen
            controller.view.backgroundColor = .black
            box.controller = controller
            // Sideways allowed first: iOS refuses a landscape-only screen otherwise.
            Orientation.landscape(true)
            top.present(controller, animated: true)
        }
    }

    private final class HostBox { weak var controller: UIViewController? }

    private static func topController() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap { $0.windows }.first { $0.isKeyWindow }
        var top = window?.rootViewController
        while let next = top?.presentedViewController { top = next }
        return top
    }
}
