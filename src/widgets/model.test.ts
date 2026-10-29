import { describe, expect, it } from "vitest";
import { MemoryStore } from "@/data/memory";
import type { Profile } from "@/data/types";
import { getHome } from "@/queries/home";
import { buildWidgetSnapshot, widgetMetric, WIDGET_KEYS } from "./model";

const profile: Profile = { birthDate: "1990-01-01", sex: "male", maxHr: 183, heightCm: 178, timeZone: "Asia/Kolkata" };

describe("widget projection", () => {
  it("preserves a real zero and an above-100 strain, capping only the progress track", () => {
    expect(widgetMetric("steps", { value: 0, reason: null, provisional: false })).toMatchObject({ value: 0, display: "0", reason: null });
    expect(widgetMetric("strain", { value: 129, reason: null, provisional: true })).toMatchObject({ value: 129, display: "129", progress: 100, provisional: true });
  });
  it.each([null, NaN, Infinity, -Infinity])("does not fabricate a number for %s", (value) => {
    expect(widgetMetric("recovery", { value, reason: "calibrating", provisional: true, nightsLeft: 3 })).toMatchObject({ value: null, display: "--", progress: null, reason: "Calibrating", provisional: true });
  });
  it("projects the existing Home query, including its empty/calibration states", async () => {
    const store = new MemoryStore();
    await store.setProfile(profile);
    const sync = await store.getSyncState();
    const home = await getHome("2026-10-29", { store, profile, timeZone: profile.timeZone, today: "2026-10-29", now: 1793260800, sync });
    const snapshot = buildWidgetSnapshot(home, { generatedAt: 1793260800, lastSyncTs: null, source: "health_connect", timeZone: profile.timeZone });
    expect(snapshot.metrics.map((m) => m.key)).toEqual(WIDGET_KEYS);
    expect(snapshot.metrics.find((m) => m.key === "recovery")?.value).toBe(home.dials.recovery.value);
    expect(snapshot.metrics.find((m) => m.key === "hrv")?.value).toBeNull();
    expect(snapshot.metrics.find((m) => m.key === "stress")?.value).toBeNull();
    expect(snapshot.metrics.every((m) => m.value === null || Number.isFinite(m.value))).toBe(true);
    expect(snapshot).toMatchObject({ day: home.day, source: "health_connect", lastSyncTs: null });
    home.stress = { value: { value: 72, level: "high", at: 1793260800000, dayAverage: false }, reason: null, provisional: true };
    expect(buildWidgetSnapshot(home, { generatedAt: 1793260800, lastSyncTs: 1793260700, source: "demo", timeZone: profile.timeZone }).metrics.find((m) => m.key === "stress")).toMatchObject({ value: 72, provisional: true });
  });
});
