import KSPlayer
import LumenKit
import SwiftUI
import UIKit

/// KSPlayer's own player view (controls, audio / subtitle tracks, Picture in Picture, AirPlay), full screen. Closing
/// it reports where the viewer stopped, in milliseconds.
struct PlayerScreen: View {
    let request: PlayRequest
    let url: URL
    let onClose: (Int64, Int64) -> Void
    @State private var controller: PlayerController?

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            KSPlayerHost(request: request, url: url) { controller = $0 }.ignoresSafeArea()
            Button {
                let (position, duration) = controller?.progress() ?? (0, 0)
                controller?.stop()
                onClose(position, duration)
            } label: {
                Image(systemName: "xmark").font(.headline).foregroundColor(.white).padding(10).background(.ultraThinMaterial, in: Circle())
            }
            .padding(.leading, 16).padding(.top, 8)
        }
    }
}

private struct KSPlayerHost: UIViewControllerRepresentable {
    let request: PlayRequest
    let url: URL
    let onReady: (PlayerController) -> Void

    func makeUIViewController(context: Context) -> PlayerController {
        let c = PlayerController(request: request, url: url)
        DispatchQueue.main.async { onReady(c) }
        return c
    }

    func updateUIViewController(_ controller: PlayerController, context: Context) {}

    static func dismantleUIViewController(_ controller: PlayerController, coordinator: ()) { controller.stop() }
}

final class PlayerController: UIViewController {
    private let request: PlayRequest
    private let url: URL
    private let playerView = IOSVideoPlayerView()

    init(request: PlayRequest, url: URL) {
        self.request = request
        self.url = url
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.addSubview(playerView)
        playerView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            playerView.topAnchor.constraint(equalTo: view.topAnchor),
            playerView.leftAnchor.constraint(equalTo: view.leftAnchor),
            playerView.rightAnchor.constraint(equalTo: view.rightAnchor),
            playerView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        // As in phase 0 (and another-iptv-player): AVPlayer for mp4 / mov / m3u8, FFmpeg for MKV, AVI and live TS;
        // the other engine when the first fails.
        let native = ["mp4", "m4v", "mov", "m3u8"].contains(url.pathExtension.lowercased())
        KSOptions.firstPlayerType = native ? KSAVPlayer.self : KSMEPlayer.self
        KSOptions.secondPlayerType = native ? KSMEPlayer.self : KSAVPlayer.self
        let options = KSOptions()
        options.userAgent = request.userAgent ?? "Lumen TV"
        if let referer = request.referer { options.appendHeader(["Referer": referer]) }
        if request.startMs > 0 { options.startPlayTime = TimeInterval(request.startMs) / 1000 }
        playerView.set(url: url, options: options)
    }

    /// Where the viewer is and the length, in milliseconds (0 for a live channel or when unknown).
    func progress() -> (Int64, Int64) {
        guard !request.live, let player = playerView.playerLayer?.player else { return (0, 0) }
        let position = player.currentPlaybackTime, duration = player.duration
        guard position.isFinite, duration.isFinite, duration > 0 else { return (0, 0) }
        return (Int64(position * 1000), Int64(duration * 1000))
    }

    func stop() { playerView.resetPlayer() }
}
