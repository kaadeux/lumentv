import AVFoundation
import Combine
import KSPlayer
import MediaPlayer
import LumenKit
import SwiftUI
import UIKit

/// Lumen TV's colours (Palette in :phoneui).
enum Lumen {
    static let accent = Color(red: 1, green: 0x7A / 255, blue: 0x2F / 255)
    static let text = Color(white: 0.95)
    static let dim = Color(white: 0.63)
    static let glass = Color.black.opacity(0.45)
}

/// The words that name a language in track names ("VFF", "English", "[fr]"), by the settings' code ("fr", "en").
enum Lang {
    static func words(_ code: String) -> Set<String> {
        code == "en" ? ["en", "eng", "english", "anglais", "vo"] : ["fr", "fre", "fra", "french", "français", "francais", "vf", "vff", "vfq"]
    }

    static func matches(_ name: String, code: String?, lang: String) -> Bool {
        if let code, words(lang).contains(code.lowercased()) { return true }
        let tokens = Set(name.lowercased().split { !$0.isLetter }.map(String.init))
        return !tokens.isDisjoint(with: words(lang))
    }
}

/// The player's KSPlayer options: more decoded frames ready (live TS stuttered with KSPlayer's 4), a longer buffer
/// for IPTV servers, the audio track in the language of the settings first.
final class LumenOptions: KSOptions {
    var audioLang = "fr"

    override func videoFrameMaxCount(fps _: Float, naturalSize _: CGSize, isLive _: Bool) -> UInt8 { 16 }

    override func wantedAudio(tracks: [MediaPlayerTrack]) -> Int? {
        tracks.firstIndex { Lang.matches($0.name, code: $0.languageCode, lang: audioLang) }
    }
}

/// How the picture fills the screen, as another-iptv-player offers it.
enum Fit: String, CaseIterable, Identifiable {
    case fit = "Adapter", fill = "Remplir", stretch = "Étirer", wide = "16:9", classic = "4:3"
    var id: String { rawValue }
    var ratio: CGFloat? { self == .wide ? 16 / 9 : self == .classic ? 4 / 3 : nil }
    var mode: UIView.ContentMode { self == .fill ? .scaleAspectFill : self == .fit ? .scaleAspectFit : .scaleToFill }
}

/// What comes after closing: nothing, or the episode before / after.
enum Step { case none, previous, next }

/// The player, full screen and sideways: the picture, and Lumen TV's controls shown on a tap (hidden again after a
/// few seconds while it plays). Closing reports where the viewer stopped, in milliseconds.
struct PlayerScreen: View {
    let request: PlayRequest
    let onClose: (Int64, Int64, Step) -> Void
    /// The stream: a live channel's lower quality replaces it when the picture keeps stopping.
    @State private var url: URL

    init(request: PlayRequest, url: URL, onClose: @escaping (Int64, Int64, Step) -> Void) {
        self.request = request
        self.onClose = onClose
        _url = State(initialValue: url)
    }

    @StateObject private var player = KSVideoPlayer.Coordinator()
    @State private var controls = true
    @State private var playing = false
    @State private var loading = true
    @State private var failed = false
    @State private var fit = Fit.fit
    @State private var brightness = Double(UIScreen.main.brightness)
    /// The iPhone's own volume (the side buttons' one), not the player's.
    @State private var volume = Double(AVAudioSession.sharedInstance().outputVolume)
    /// Live paused: since when, and how far behind live the picture is (pauses added up).
    @State private var pausedAt: Date?
    @State private var behind: TimeInterval = 0
    @State private var scrubbing: Double?
    @State private var hide: DispatchWorkItem?
    @State private var closed = false
    // Live: stalls seen once it played, and the lower qualities already tried.
    @State private var played = false
    @State private var stalls: [Date] = []
    @State private var bufferingSince: Date?
    @State private var variant = 0
    /// The plain HTTP address was tried after the HTTPS one failed.
    @State private var triedPlain = false
    /// When it fails: the network's fault, the provider's server's or the stream's (shared check).
    @State private var why: String?
    @State private var notice: String?
    /// The recap / intro / credits being played, to skip.
    @State private var skip: PlaySkip?
    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var options: KSOptions {
        let o = LumenOptions()
        o.audioLang = request.audioLang
        // The subtitles are chosen here (settings: forced only, French, English), not the file's first one.
        o.autoSelectEmbedSubtitle = false
        o.userAgent = request.userAgent ?? "Lumen TV"
        if let referer = request.referer { o.appendHeader(["Referer": referer]) }
        if request.startMs > 0 { o.startPlayTime = TimeInterval(request.startMs) / 1000 }
        o.preferredForwardBufferDuration = request.live ? 3 : 5
        // Live: a minute kept, so a pause picks up where it stopped.
        o.maxBufferDuration = 60
        return o
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            video.ignoresSafeArea()
            SystemVolume.Host().frame(width: 1, height: 1).opacity(0.01).allowsHitTesting(false)
            subtitles
            // Taps on the picture (the video view itself is UIKit and would keep them).
            Color.clear.contentShape(Rectangle()).ignoresSafeArea()
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { controls.toggle() }; if controls { scheduleHide() } }
            if loading && !failed { ProgressView().tint(Lumen.accent).scaleEffect(1.6).allowsHitTesting(false) }
            if failed { message("Lecture impossible", why ?? "Vérification de la connexion…") }
            if controls { overlay.transition(.opacity) }
            if let s = skip { skipButton(s) }
            if let n = notice {
                VStack {
                    Text(n).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                        .padding(.horizontal, 16).padding(.vertical, 9).background(Lumen.glass, in: Capsule())
                    Spacer()
                }
                .padding(.top, 72).allowsHitTesting(false)
            }
        }
        .onReceive(clock) { _ in tick() }
        .onChange(of: failed) { f in
            guard f else { return }
            why = nil
            request.explain { text in DispatchQueue.main.async { why = text.isEmpty ? "Le serveur ne répond pas ou le format n'est pas lu." : text } }
        }
        // The side buttons move the pill too.
        .onReceive(AVAudioSession.sharedInstance().publisher(for: \.outputVolume)) { v in volume = Double(v) }
        .statusBarHidden(!controls)
        .persistentSystemOverlays(.hidden)
        .preferredColorScheme(.dark)
        .onAppear { scheduleHide() }
        .onDisappear { UIScreen.main.brightness = CGFloat(brightness) }
    }

    // ---- The picture ---------------------------------------------------------------------------------------------

    private var video: some View {
        KSVideoPlayer(coordinator: player, url: url, options: options)
            .onStateChanged { layer, state in
                playing = state.isPlaying
                loading = state == .preparing || state == .buffering || state == .initialized
                if state == .readyToPlay { layer.player.contentMode = fit.mode; pickSubtitles() }
                if state == .error {
                    // Its HTTPS address first refused: plain HTTP, as before. Then a live channel tries its lower quality.
                    if plainAgain() { return }
                    if request.live && behind > 0 { goLive(); return }
                    if request.live && variant < request.variants.count { stepDown() } else { failed = true; controls = true }
                }
                if state == .bufferFinished { played = true; bufferingSince = nil; scheduleHide() }
                if state == .buffering, played, request.live {
                    let now = Date()
                    bufferingSince = now
                    stalls = stalls.filter { now.timeIntervalSince($0) < 120 } + [now]
                    if stalls.count >= 2 { stepDown() }
                }
            }
            .onFinish { _, error in
                if error == nil, !request.live { close(finished: true, step: request.hasNext && request.autoNext ? .next : .none) }
                else if error != nil, !plainAgain() { failed = true; controls = true }
            }
            .modifier(Ratio(ratio: fit.ratio))
    }

    /// The subtitles chosen (embedded or not), above the picture.
    private var subtitles: some View {
        SubtitleText(model: player.subtitleModel).allowsHitTesting(false)
    }

    // ---- The controls --------------------------------------------------------------------------------------------

    private var overlay: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.65), .clear, .clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea().allowsHitTesting(false)
            VStack(spacing: 0) {
                topBar
                Spacer()
                HStack {
                    VerticalPill(value: $brightness, icon: "sun.max.fill") { UIScreen.main.brightness = CGFloat($0); scheduleHide() }
                    Spacer()
                    if request.live { livePause } else { centerButtons }
                    Spacer()
                    VerticalPill(value: $volume, icon: volume == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill") {
                        SystemVolume.set(Float($0)); scheduleHide()
                    }
                }
                Spacer()
                bottomBar
            }
            .padding(.horizontal, 24).padding(.vertical, 14)
        }
    }

    private var topBar: some View {
        HStack(alignment: .center, spacing: 14) {
            RoundButton(icon: "xmark") { close(finished: false, step: .none) }
            VStack(alignment: .leading, spacing: 2) {
                Text(request.title).font(.headline).foregroundColor(Lumen.text).lineLimit(1)
                if let s = request.subtitle { Text(s).font(.subheadline).foregroundColor(Lumen.dim).lineLimit(1) }
            }
            Spacer()
            HStack(spacing: 22) {
                Button { player.playerLayer?.isPipActive.toggle() } label: { Image(systemName: "pip.enter") }
                subtitleMenu
                settingsMenu
            }
            .font(.system(size: 19, weight: .semibold)).foregroundColor(Lumen.text)
            .padding(.horizontal, 20).frame(height: 46)
            .background(Lumen.glass, in: Capsule()).overlay(Capsule().stroke(.white.opacity(0.12)))
        }
    }

    /// Live: pause (the stream keeps coming in for a minute), then play on from there.
    private var livePause: some View {
        Button {
            if let since = pausedAt {
                pausedAt = nil
                behind += Date().timeIntervalSince(since)
                // Past what is kept: straight back to live.
                if behind > 55 { goLive(); notice = "Reprise au direct"; DispatchQueue.main.asyncAfter(deadline: .now() + 3) { notice = nil } }
                else { player.playerLayer?.play() }
                scheduleHide()
            } else {
                pausedAt = Date()
                player.playerLayer?.pause()
                hide?.cancel()
            }
        } label: {
            Image(systemName: pausedAt != nil ? "play.fill" : "pause.fill").font(.system(size: 34, weight: .bold))
                .frame(width: 76, height: 76).background(Lumen.accent, in: Circle())
        }
        .foregroundColor(.white)
    }

    /// The live picture again: the stream opened anew.
    private func goLive() {
        pausedAt = nil
        behind = 0
        stalls = []
        bufferingSince = nil
        played = false
        failed = false
        // As KSPlayer does for a new address: stop, open it again, play.
        if let layer = player.playerLayer {
            layer.stop()
            layer.player.replace(url: url, options: layer.options)
            layer.play()
        }
        scheduleHide()
    }

    private var centerButtons: some View {
        HStack(spacing: 56) {
            Button { player.skip(interval: -15); scheduleHide() } label: { Image(systemName: "gobackward.15").font(.system(size: 36, weight: .medium)) }
            Button {
                if playing { player.playerLayer?.pause() } else { player.playerLayer?.play() }
                scheduleHide()
            } label: {
                Image(systemName: playing ? "pause.fill" : "play.fill").font(.system(size: 34, weight: .bold))
                    .frame(width: 76, height: 76).background(Lumen.accent, in: Circle())
            }
            Button { player.skip(interval: 15); scheduleHide() } label: { Image(systemName: "goforward.15").font(.system(size: 36, weight: .medium)) }
        }
        .foregroundColor(.white)
    }

    private var bottomBar: some View {
        Group {
            if request.live {
                HStack(spacing: 12) {
                    if pausedAt == nil && behind < 1 {
                        Text("● EN DIRECT").font(.caption.bold()).foregroundColor(.white)
                            .padding(.horizontal, 10).padding(.vertical, 5).background(Color.red.opacity(0.85), in: Capsule())
                    } else {
                        Text(pausedAt != nil ? "❚❚ EN PAUSE" : "EN DIFFÉRÉ").font(.caption.bold()).foregroundColor(.white)
                            .padding(.horizontal, 10).padding(.vertical, 5).background(Lumen.glass, in: Capsule())
                        Button { goLive() } label: {
                            Label("Revenir au direct", systemImage: "forward.end.fill").font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                .padding(.horizontal, 14).padding(.vertical, 8).background(Color.red.opacity(0.85), in: Capsule())
                        }
                    }
                    Spacer()
                }
            } else {
                VStack(alignment: .trailing, spacing: 12) {
                    if request.hasPrevious || request.hasNext {
                        HStack(spacing: 12) {
                            if request.hasPrevious { RoundButton(icon: "backward.end.fill") { close(finished: false, step: .previous) } }
                            if request.hasNext { RoundButton(icon: "forward.end.fill") { close(finished: false, step: .next) } }
                        }
                    }
                    TimeBar(model: player.timemodel, scrubbing: $scrubbing) { seconds in
                        player.seek(time: seconds); scheduleHide()
                    }
                }
            }
        }
    }

    private var subtitleMenu: some View {
        Menu {
            Button { player.subtitleModel.selectedSubtitleInfo = nil } label: { Label("Désactivés", systemImage: player.subtitleModel.selectedSubtitleInfo == nil ? "checkmark" : "") }
            ForEach(player.subtitleModel.subtitleInfos, id: \.subtitleID) { info in
                Button { player.subtitleModel.selectedSubtitleInfo = info } label: {
                    Label(info.name, systemImage: player.subtitleModel.selectedSubtitleInfo?.subtitleID == info.subtitleID ? "checkmark" : "")
                }
            }
        } label: { Image(systemName: "captions.bubble") }
    }

    private var settingsMenu: some View {
        Menu {
            Section("Format de l'image") {
                ForEach(Fit.allCases) { f in
                    Button { fit = f; player.playerLayer?.player.contentMode = f.mode } label: { Label(f.rawValue, systemImage: fit == f ? "checkmark" : "") }
                }
            }
            if let tracks = player.playerLayer?.player.tracks(mediaType: .audio), tracks.count > 1 {
                Section("Langue") {
                    ForEach(tracks, id: \.trackID) { t in
                        Button { player.playerLayer?.player.select(track: t) } label: { Label(t.description, systemImage: t.isEnabled ? "checkmark" : "") }
                    }
                }
            }
            if !request.live {
                Section("Vitesse") {
                    ForEach([0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { r in
                        Button { player.playbackRate = Float(r) } label: {
                            Label(r == 1 ? "Normale" : "×\(r.formatted())", systemImage: Double(player.playbackRate) == r ? "checkmark" : "")
                        }
                    }
                }
            }
        } label: { Image(systemName: "gearshape") }
    }

    private func message(_ title: String, _ text: String) -> some View {
        VStack(spacing: 6) {
            Text(title).font(.headline).foregroundColor(Lumen.text)
            Text(text).font(.subheadline).foregroundColor(Lumen.dim).multilineTextAlignment(.center)
        }
        .padding(20).background(Lumen.glass, in: RoundedRectangle(cornerRadius: 16))
    }

    // ---- Behaviour -----------------------------------------------------------------------------------------------

    /// The controls go away 4 s after the last touch while the film plays.
    private func scheduleHide() {
        hide?.cancel()
        let work = DispatchWorkItem { if playing && scrubbing == nil && pausedAt == nil { withAnimation(.easeInOut(duration: 0.3)) { controls = false } } }
        hide = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: work)
    }

    /// As on the TV: the forced subtitles in the audio's language (the lines in another language), or the full ones
    /// of the language chosen in the settings.
    private func pickSubtitles() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            guard player.subtitleModel.selectedSubtitleInfo == nil else { return }
            let infos = player.subtitleModel.subtitleInfos
            let full = request.subtitlesOn
            let lang = full ? request.subtitleLang : request.audioLang
            func forced(_ name: String) -> Bool { name.lowercased().contains("forc") }
            let pick = infos.first { Lang.matches($0.name, code: nil, lang: lang) && forced($0.name) != full }
                ?? (full ? infos.first { Lang.matches($0.name, code: nil, lang: lang) } : nil)
            if let pick { player.subtitleModel.selectedSubtitleInfo = pick }
        }
    }

    /// Once a second: a stall longer than 6 s steps a live channel down; the skip button follows the episode.
    private func tick() {
        if let since = bufferingSince, request.live, Date().timeIntervalSince(since) > 6 { stepDown() }
        let ms = Int64(player.timemodel.currentTime) * 1000
        let current = request.skips.first { $0.startMs <= ms && ms < $0.endMs - 1000 }
        if current?.label != skip?.label || current?.startMs != skip?.startMs { skip = current }
    }

    /// The server took the API over HTTPS but not this stream: its plain HTTP address, once.
    private func plainAgain() -> Bool {
        guard !triedPlain, variant == 0, let plain = request.fallbackUrl, let next = URL(string: plain) else { return false }
        triedPlain = true
        failed = false
        url = next
        return true
    }

    /// The same channel one quality lower (the next of the request's variants), with a word on screen.
    private func stepDown() {
        guard request.live, variant < request.variants.count, let next = URL(string: request.variants[variant].url) else { return }
        notice = "Connexion lente : passage en " + request.variants[variant].label
        variant += 1
        stalls = []
        bufferingSince = nil
        played = false
        failed = false
        url = next
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { notice = nil }
    }

    private func skipButton(_ s: PlaySkip) -> some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                Button {
                    skip = nil
                    if s.toNext && request.hasNext { close(finished: true, step: .next) }
                    else { player.seek(time: TimeInterval(s.endMs) / 1000) }
                } label: {
                    Text(s.label + "  ›").font(.subheadline.weight(.bold)).foregroundColor(.black)
                        .padding(.horizontal, 18).padding(.vertical, 11).background(Color.white, in: Capsule())
                }
            }
        }
        .padding(.trailing, 32).padding(.bottom, controls ? 120 : 36)
    }

    private func close(finished: Bool, step: Step) {
        guard !closed else { return }
        closed = true
        var position: Int64 = 0, duration: Int64 = 0
        if !request.live, let p = player.playerLayer?.player, p.duration.isFinite, p.duration > 0 {
            duration = Int64(p.duration * 1000)
            position = finished ? duration : Int64(max(0, p.currentPlaybackTime) * 1000)
        }
        player.resetPlayer()
        onClose(position, duration, step)
    }
}

/// 16:9 and 4:3 frame the picture to that shape; the other formats take the whole screen (KSPlayer then fits,
/// fills or stretches inside it).
private struct Ratio: ViewModifier {
    let ratio: CGFloat?
    func body(content: Content) -> some View {
        if let ratio {
            content.aspectRatio(ratio, contentMode: .fit).frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            content.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// A tall rounded slider (brightness on the left, sound on the right), dragged up and down.
private struct VerticalPill: View {
    @Binding var value: Double
    let icon: String
    let onChange: (Double) -> Void

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: 26).fill(Lumen.glass)
                RoundedRectangle(cornerRadius: 26).fill(Color.white.opacity(0.92)).frame(height: g.size.height * value)
                Image(systemName: icon).font(.system(size: 20, weight: .semibold))
                    .foregroundColor(value > 0.18 ? .black : .white).padding(.bottom, 18)
            }
            .clipShape(RoundedRectangle(cornerRadius: 26))
            .overlay(RoundedRectangle(cornerRadius: 26).stroke(.white.opacity(0.12)))
            .gesture(DragGesture(minimumDistance: 0).onChanged { d in
                let y = Double(d.location.y), h = Double(max(1, g.size.height))
                value = min(1.0, max(0.0, 1.0 - y / h))
                onChange(value)
            })
        }
        .frame(width: 60, height: 170)
    }
}

/// Times and the bar to move in the film, in Lumen TV's orange.
private struct TimeBar: View {
    @ObservedObject var model: ControllerTimeModel
    @Binding var scrubbing: Double?
    let onSeek: (TimeInterval) -> Void

    var body: some View {
        let total = Double(max(1, model.totalTime))
        HStack(spacing: 14) {
            Text(clock(scrubbing ?? Double(model.currentTime))).monospacedDigit()
            Slider(value: Binding(get: { scrubbing ?? Double(model.currentTime) }, set: { scrubbing = $0 }), in: 0...total) { editing in
                if !editing, let s = scrubbing { onSeek(s); scrubbing = nil }
            }
            .tint(Lumen.accent)
            Text(clock(total)).monospacedDigit()
        }
        .font(.subheadline.weight(.medium)).foregroundColor(Lumen.text)
    }

    private func clock(_ s: Double) -> String {
        let t = Int(s), h = t / 3600, m = (t % 3600) / 60, sec = t % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }
}

/// The current subtitle lines, white with a shadow, at the bottom of the picture.
private struct SubtitleText: View {
    @ObservedObject var model: SubtitleModel

    var body: some View {
        VStack {
            Spacer()
            ForEach(model.parts) { part in
                if let image = part.image {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 120)
                } else if let text = part.text {
                    Text(AttributedString(text)).font(.system(size: 20, weight: .semibold)).foregroundColor(.white)
                        .multilineTextAlignment(.center).shadow(color: .black, radius: 2).shadow(color: .black, radius: 4)
                }
            }
        }
        .padding(.horizontal, 40).padding(.bottom, 28)
    }
}

/// A round glass button (close).
private struct RoundButton: View {
    let icon: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 18, weight: .bold)).foregroundColor(.white)
                .frame(width: 46, height: 46).background(Lumen.glass, in: Circle()).overlay(Circle().stroke(.white.opacity(0.12)))
        }
    }
}

/// Sideways while a player is open; the app's screens stay upright. Counted: at an episode change the next player
/// opens while the previous one closes, and the screen must not turn upright in between.
enum Orientation {
    private static var open = 0
    static var landscapeOnly: Bool { open > 0 }

    static func landscape(_ on: Bool) {
        open = max(0, open + (on ? 1 : -1))
        if on { apply() } else {
            // Upright only once no player is left a moment later (the next episode's may be on its way).
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { if open == 0 { apply() } }
        }
    }

    private static func apply() {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        var vc = scene.windows.first?.rootViewController
        while let c = vc { c.setNeedsUpdateOfSupportedInterfaceOrientations(); vc = c.presentedViewController }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: landscapeOnly ? .landscape : .portrait)) { _ in }
    }
}

/// The player's screen: iOS shows it sideways from the start, its controls with it.
final class LandscapeHost<Content: View>: UIHostingController<Content> {
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { .landscapeRight }
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
}

/// The iPhone's volume, as the side buttons set it: iOS lets an app change it only through MPVolumeView's slider
/// (shown off screen; it also keeps iOS's own volume banner away while the player's pill moves).
enum SystemVolume {
    private static let view: MPVolumeView = {
        let v = MPVolumeView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
        return v
    }()

    static func set(_ value: Float) {
        guard let slider = view.subviews.compactMap({ $0 as? UISlider }).first else { return }
        DispatchQueue.main.async { slider.setValue(min(1, max(0, value)), animated: false); slider.sendActions(for: .valueChanged) }
    }

    /// Puts the view in the player's window (needed for the slider to work).
    struct Host: UIViewRepresentable {
        func makeUIView(context _: Context) -> UIView {
            let box = UIView()
            view.removeFromSuperview()
            box.addSubview(view)
            return box
        }
        func updateUIView(_: UIView, context _: Context) {}
    }
}
