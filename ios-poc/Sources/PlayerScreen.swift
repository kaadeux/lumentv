import KSPlayer
import SwiftUI
import UIKit

/// KSPlayer's own player view (controls, audio / subtitle tracks, Picture in Picture, AirPlay), full screen, with
/// the engine actually playing shown at the top (AVPlayer or FFmpeg).
struct PlayerScreen: View {
    let url: URL
    let title: String
    let engine: Engine
    let onClose: () -> Void
    @State private var info = "Ouverture…"

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            KSPlayerHost(url: url, title: title, engine: engine) { info = $0 }.ignoresSafeArea()
            HStack(spacing: 10) {
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.headline).padding(10).background(.ultraThinMaterial, in: Circle())
                }
                Text(info).font(.caption.monospaced()).padding(.horizontal, 10).padding(.vertical, 6)
                    .background(.ultraThinMaterial, in: Capsule())
            }
            .padding(.leading, 16).padding(.top, 8)
        }
    }
}

private struct KSPlayerHost: UIViewControllerRepresentable {
    let url: URL
    let title: String
    let engine: Engine
    let onInfo: (String) -> Void

    func makeUIViewController(context: Context) -> PlayerController {
        PlayerController(url: url, title: title, engine: engine, onInfo: onInfo)
    }

    func updateUIViewController(_ controller: PlayerController, context: Context) {}

    static func dismantleUIViewController(_ controller: PlayerController, coordinator: ()) { controller.stop() }
}

final class PlayerController: UIViewController {
    private let url: URL
    private let name: String
    private let engine: Engine
    private let onInfo: (String) -> Void
    private let playerView = IOSVideoPlayerView()
    private var timer: Timer?

    init(url: URL, title: String, engine: Engine, onInfo: @escaping (String) -> Void) {
        self.url = url
        self.name = title
        self.engine = engine
        self.onInfo = onInfo
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
        // Engines in the order wanted (another-iptv-player: by the file's extension, the other one on failure).
        let native = ["mp4", "m4v", "mov", "m3u8"].contains(url.pathExtension.lowercased())
        switch engine {
        case .auto:
            KSOptions.firstPlayerType = native ? KSAVPlayer.self : KSMEPlayer.self
            KSOptions.secondPlayerType = native ? KSMEPlayer.self : KSAVPlayer.self
        case .ffmpeg:
            KSOptions.firstPlayerType = KSMEPlayer.self
            KSOptions.secondPlayerType = nil
        case .avplayer:
            KSOptions.firstPlayerType = KSAVPlayer.self
            KSOptions.secondPlayerType = nil
        }
        let options = KSOptions()
        options.userAgent = "Lumen TV"
        playerView.set(url: url, options: options)
        let started = Date()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self, let layer = self.playerView.playerLayer else { return }
            let kind = layer.player is KSMEPlayer ? "FFmpeg" : "AVPlayer"
            let size = layer.player.naturalSize
            let video = size.width > 0 ? " · \(Int(size.width))×\(Int(size.height))" : ""
            let state = "\(layer.state)"
            self.onInfo("\(kind)\(video) · \(state) · \(Int(Date().timeIntervalSince(started))) s")
        }
    }

    func stop() {
        timer?.invalidate()
        playerView.resetPlayer()
    }
}
