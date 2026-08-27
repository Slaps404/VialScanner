import SwiftUI
import VialCore

struct HistoryView: View {
    let openMenu: () -> Void
    @EnvironmentObject private var store: AppStore

    @State private var filter: ScanOutcome?
    @State private var selected: ScanRecord?
    @State private var query = ""

    var body: some View {
        Screen(title: "Scan history", onLeading: openMenu) {
            VStack(spacing: 12) {
                stats

                SearchField(placeholder: "AbNo, antibody, lot, catalog…", text: $query)
                    .padding(.horizontal, Theme.pad)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        chip(nil, "All")
                        ForEach(ScanOutcome.allCases, id: \.self) { o in
                            chip(o, o.label)
                        }
                    }
                    .padding(.horizontal, Theme.pad)
                }

                if results.isEmpty {
                    EmptyState(icon: "clock",
                               title: "No scans yet",
                               message: "Every scan is logged here with the verdict, the evidence, and the clip if one was kept.")
                    Spacer()
                } else {
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(results) { rec in
                                Button { selected = rec } label: { row(rec) }
                                    .buttonStyle(PressScale())
                            }
                        }
                        .padding(.horizontal, Theme.pad)
                        .padding(.bottom, 28)
                    }
                }
            }
        }
        .sheet(item: $selected) { rec in
            ReviewDetail(record: rec).environmentObject(store)
        }
    }

    private var stats: some View {
        let today = store.scans.filter { Calendar.current.isDateInToday($0.date) }
        let hits = today.filter { $0.verdictValue == .certain || $0.verdictValue == .confident }.count
        return HStack(spacing: 10) {
            statTile(value: "\(today.count)", label: "scans today")
            statTile(value: "\(hits)", label: "identified")
            statTile(value: "\(store.reviewQueue.count)", label: "in review")
        }
        .padding(.horizontal, Theme.pad)
    }

    private func statTile(value: String, label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.text)
            Text(label).font(Theme.caption).foregroundStyle(Theme.textSecond)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.fill))
    }

    private func chip(_ o: ScanOutcome?, _ label: String) -> some View {
        Button {
            filter = o
            Haptics.tap()
        } label: {
            Text(label)
                .font(Theme.caption)
                .foregroundStyle(filter == o ? Theme.onSolid : Theme.textSecond)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(Capsule().fill(filter == o ? Theme.solid : Theme.fill))
        }
        .buttonStyle(PressScale())
    }

    private var results: [ScanRecord] {
        let q = normKey(query)
        return store.scans.filter { rec in
            if let filter, rec.outcome != filter { return false }
            guard !q.isEmpty else { return true }
            return normKey(rec.abNo ?? "").contains(q)
                || normKey(rec.definitionName ?? "").contains(q)
                || normKey(rec.catalog ?? "").contains(q)
                || normKey(rec.lot ?? "").contains(q)
                || normKey(rec.vendor ?? "").contains(q)
        }
    }

    private func row(_ rec: ScanRecord) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(rec.verdictValue.tint.opacity(0.14)).frame(width: 40, height: 40)
                Image(systemName: rec.verdictValue.glyph)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(rec.verdictValue.tint)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(rec.title).font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Theme.text).lineLimit(1)
                Text([rec.subtitle, AppStore.stamp(rec.date)]
                    .filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(Theme.caption).foregroundStyle(Theme.textSecond).lineLimit(1)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 4) {
                Chip(text: rec.outcome.label,
                     tint: rec.outcome == .review ? Theme.warn : Theme.textSecond)
                if rec.clipFile != nil {
                    Image(systemName: "video.fill")
                        .font(.system(size: 10)).foregroundStyle(Theme.accent)
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.surface))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.hairline))
    }
}
