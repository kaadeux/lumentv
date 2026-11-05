import KSPlayer
import LumenKit
import SwiftUI
import UIKit

/// The shared app's player on the iPhone (VideoPlayer in :phoneui): KSPlayer full screen over the app.
final class LumenPlayer: NSObject, VideoPlayer {
    static let shared = LumenPlayer()

    func play(request: PlayRequest) {
        DispatchQueue.main.async {
            guard let url = URL(string: request.url), let top = Self.topController() else { return }
            let box = HostBox()
            let screen = PlayerScreen(request: request, url: url) { position, duration in
                box.controller?.dismiss(animated: true)
                request.finish(positionMs: position, durationMs: duration)
            }
            let controller = UIHostingController(rootView: screen)
            controller.modalPresentationStyle = .fullScreen
            controller.view.backgroundColor = .black
            box.controller = controller
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
