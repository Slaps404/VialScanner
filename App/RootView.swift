import SwiftUI
import VialCore

enum Route: String, CaseIterable, Identifiable {
    case scan, inventory, review, audit, history, data, settings
    var id: String { rawValue }

    var title: String {
        switch self {
        case .scan:      return "Scan"
        case .inventory: return "Inventory"
        case .review:    return "Review queue"
        case .audit:     return "Confirm inventory"
        case .history:   return "Scan history"
        case .data:      return "Import & export"
        case .settings:  return "Settings"
        }
    }
    var icon: String {
        switch self {
        case .scan:      return "viewfinder"
        case .inventory: return "tray.full"
        case .review:    return "clock.badge.exclamationmark"
        case .audit:     return "checklist"
        case .history:   return "list.bullet.rectangle"
        case .data:      return "arrow.up.arrow.down.square"
        case .settings:  return "gearshape"
        }
    }
}

/// The shell. A slide-over drawer with the page pushed and scaled behind it,
/// following the reference app — no tab bar, no navigation chrome, because in
/// use this app is a viewfinder with a menu, not a document browser.
struct RootView: View {
    @StateObject private var store = AppStore()
    @StateObject private var camera = CameraController(catalog: Catalog(definitions: [], items: []))

    @State private var route: Route = .scan
    @State private var drawerOpen = false
    @State private var dragX: CGFloat = 0

    private let drawerWidth: CGFloat = 288

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Theme.bg.ignoresSafeArea()

                page
                    .frame(width: geo.size.width, height: geo.size.height)
                    .background(Theme.bg)
                    .clipShape(RoundedRectangle(cornerRadius: openness > 0 ? 32 : 0, style: .continuous))
                    .shadow(color: .black.opacity(0.18 * openness), radius: 24, x: -6, y: 0)
                    .scaleEffect(1 - 0.06 * openness, anchor: .center)
                    .offset(x: offsetX * 0.3)
                    .overlay {
                        if openness > 0.01 {
                            Color.black.opacity(0.35 * openness)
                                .ignoresSafeArea()
                                .onTapGesture { closeDrawer() }
                        }
                    }
                    .allowsHitTesting(!drawerOpen && openness < 0.01)

                DrawerView(route: $route, open: $drawerOpen)
                    .frame(width: drawerWidth)
                    .environmentObject(store)
                    .offset(x: -drawerWidth * (1 - openness))
                    .overlay(alignment: .trailing) {
                        Rectangle()
                            .fill(Theme.hairline)
                            .frame(width: 1)
                            .opacity(openness)
                    }
                    .shadow(color: .black.opacity(0.25 * openness), radius: 20, x: 6, y: 0)
                    .allowsHitTesting(drawerOpen || openness > 0.05)
            }
            .gesture(edgeDrag)
        }
        .environmentObject(store)
        .preferredColorScheme(nil)
        .task {
            Speaker.shared.configureAudioSession()
            camera.reconfigure(catalog: store.catalog, config: store.settings.engineConfig())
            Haptics.enabled = store.settings.haptics
            Speaker.shared.enabled = store.settings.speak
        }
        .onChange(of: store.catalog.items.count) { _, _ in syncEngine() }
        .onChange(of: store.catalog.definitions.count) { _, _ in syncEngine() }
        .onChange(of: store.settings.maxScanSeconds) { _, _ in syncEngine() }
        .onChange(of: store.settings.roiInset) { _, _ in syncEngine() }
        .onChange(of: store.settings.haptics) { _, on in Haptics.enabled = on }
        .onChange(of: store.settings.speak) { _, on in Speaker.shared.enabled = on }
        .overlay(alignment: .top) { bannerView }
    }

    private func syncEngine() {
        camera.reconfigure(catalog: store.catalog, config: store.settings.engineConfig())
    }

    @ViewBuilder
    private var page: some View {
        switch route {
        case .scan:
            ScanView(camera: camera, openMenu: openDrawer, goTo: { route = $0 })
        case .inventory:
            InventoryView(openMenu: openDrawer)
        case .review:
            ReviewView(openMenu: openDrawer)
        case .audit:
            AuditView(openMenu: openDrawer, startScanning: { route = .scan })
        case .history:
            HistoryView(openMenu: openDrawer)
        case .data:
            DataView(openMenu: openDrawer)
        case .settings:
            SettingsView(openMenu: openDrawer)
        }
    }

    // MARK: Drawer mechanics

    private var openness: CGFloat {
        max(0, min(1, offsetX / drawerWidth))
    }

    private var offsetX: CGFloat {
        let base: CGFloat = drawerOpen ? drawerWidth : 0
        return max(0, min(drawerWidth, base + dragX))
    }

    private var edgeDrag: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { v in
                // Only from the left edge when closed, so a horizontal swipe in a
                // list never fights the drawer.
                guard drawerOpen || v.startLocation.x < 28 else { return }
                dragX = v.translation.width
            }
            .onEnded { v in
                guard drawerOpen || v.startLocation.x < 28 else { dragX = 0; return }
                let projected = offsetX + v.predictedEndTranslation.width * 0.2
                withAnimation(.spring(response: 0.34, dampingFraction: 0.86)) {
                    drawerOpen = projected > drawerWidth * 0.45
                    dragX = 0
                }
            }
    }

    private func openDrawer() {
        withAnimation(.spring(response: 0.34, dampingFraction: 0.86)) { drawerOpen = true }
    }
    private func closeDrawer() {
        withAnimation(.spring(response: 0.34, dampingFraction: 0.86)) { drawerOpen = false }
    }

    // MARK: Banner

    @ViewBuilder
    private var bannerView: some View {
        if let banner = store.banner {
            HStack(spacing: 10) {
                Image(systemName: banner.isError ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                    .foregroundStyle(banner.isError ? Theme.bad : Theme.good)
                Text(banner.text)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Theme.text)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.surface))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.hairline))
            .shadow(color: .black.opacity(0.12), radius: 18, y: 6)
            .padding(.horizontal, 16)
            .transition(.move(edge: .top).combined(with: .opacity))
            .onTapGesture { store.banner = nil }
            .task(id: banner.id) {
                try? await Task.sleep(nanoseconds: 4_500_000_000)
                withAnimation { store.banner = nil }
            }
        }
    }
}

// MARK: - Drawer

struct DrawerView: View {
    @Binding var route: Route
    @Binding var open: Bool
    @EnvironmentObject private var store: AppStore

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Vial Scanner")
                    .font(.system(size: 21, weight: .bold))
                    .foregroundStyle(Theme.text)
                Spacer()
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 18)

            ForEach(Route.allCases) { r in
                Button {
                    Haptics.tap()
                    route = r
                    withAnimation(.spring(response: 0.34, dampingFraction: 0.86)) { open = false }
                } label: {
                    HStack(spacing: 14) {
                        Image(systemName: r.icon)
                            .font(.system(size: 17, weight: .medium))
                            .frame(width: 24)
                        Text(r.title)
                            .font(.system(size: 16, weight: route == r ? .semibold : .regular))
                        Spacer()
                        if let badge = badge(for: r) {
                            Text(badge)
                                .font(Theme.caption)
                                .foregroundStyle(Theme.onSolid)
                                .padding(.horizontal, 7).padding(.vertical, 3)
                                .background(Capsule().fill(Theme.solid))
                        }
                    }
                    .foregroundStyle(Theme.text)
                    .padding(.horizontal, 14)
                    .frame(height: 46)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(route == r ? Theme.fill : .clear)
                    )
                }
                .buttonStyle(PressScale())
                .padding(.horizontal, 10)
            }

            Spacer()

            VStack(alignment: .leading, spacing: 4) {
                Text("\(store.items.count) vials · \(store.defs.count) definitions")
                    .font(Theme.caption)
                    .foregroundStyle(Theme.textSecond)
                if let session = store.openAudit {
                    Text("Audit open: \(session.title)")
                        .font(Theme.caption)
                        .foregroundStyle(Theme.warn)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 22)
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .padding(.top, 54)
        .background(Theme.bg.ignoresSafeArea())
    }

    private func badge(for r: Route) -> String? {
        switch r {
        case .review:
            let n = store.reviewQueue.count
            return n > 0 ? String(n) : nil
        case .audit:
            return store.openAudit != nil ? "1" : nil
        default:
            return nil
        }
    }
}
