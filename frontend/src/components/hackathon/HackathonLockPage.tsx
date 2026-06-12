"use client";

import { Radio, SatelliteDish, ShieldAlert } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { HackathonLockConfig } from "@/lib/api";
import { getCountdownParts, type CountdownParts } from "@/lib/hackathon-countdown";

interface HackathonLockPageProps {
  config: HackathonLockConfig;
}

const COUNTDOWN_LABELS: Array<[keyof Omit<CountdownParts, "complete">, string]> = [
  ["days", "Tage"],
  ["hours", "Stunden"],
  ["minutes", "Minuten"],
  ["seconds", "Sekunden"],
];

function formatEventDate(value: string) {
  const date = new Date(`${value}T00:00:00+02:00`);
  return new Intl.DateTimeFormat("de-DE", {
    day: "2-digit",
    month: "long",
    year: "numeric",
    timeZone: "Europe/Berlin",
  }).format(date);
}

function formatTime(value: number) {
  return value.toString().padStart(2, "0");
}

export function HackathonLockPage({ config }: HackathonLockPageProps) {
  const [countdown, setCountdown] = useState(() => getCountdownParts(config.targetAt));
  const eventDate = useMemo(() => formatEventDate(config.eventDate), [config.eventDate]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setCountdown(getCountdownParts(config.targetAt));
    }, 1000);

    return () => window.clearInterval(timer);
  }, [config.targetAt]);

  return (
    <main className="relative min-h-screen overflow-hidden bg-[#050505] text-zinc-50">
      <div className="absolute inset-0 bg-[linear-gradient(rgba(255,255,255,0.055)_1px,transparent_1px),linear-gradient(90deg,rgba(255,255,255,0.045)_1px,transparent_1px)] bg-[size:44px_44px]" />
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_18%_16%,rgba(250,204,21,0.16),transparent_28%),radial-gradient(circle_at_82%_20%,rgba(34,211,238,0.12),transparent_30%),linear-gradient(140deg,rgba(127,29,29,0.28),transparent_45%)]" />
      <div className="absolute left-0 right-0 top-0 h-1 bg-gradient-to-r from-red-500 via-amber-300 to-cyan-300" />

      <section className="relative z-10 mx-auto flex min-h-screen w-full max-w-7xl flex-col px-5 py-6 sm:px-8 lg:px-10">
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-white/15 pb-4 text-xs font-semibold uppercase tracking-[0.28em] text-zinc-300">
          <div className="flex items-center gap-3">
            <span className="flex h-8 w-8 items-center justify-center border border-red-400/60 bg-red-500/15 text-red-200">
              <Radio size={16} />
            </span>
            <span>Broadcast Control</span>
          </div>
          <div className="flex items-center gap-2 text-cyan-100">
            <SatelliteDish size={16} />
            <span>Europe/Berlin 09:00</span>
          </div>
        </header>

        <div className="grid flex-1 items-center gap-10 py-10 lg:grid-cols-[minmax(0,0.95fr)_minmax(420px,1.05fr)]">
          <div className="space-y-8">
            <div className="inline-flex items-center gap-2 border border-amber-300/40 bg-amber-300/10 px-3 py-2 text-xs font-bold uppercase tracking-[0.24em] text-amber-100">
              <ShieldAlert size={16} />
              Access hold active
            </div>

            <div className="space-y-5">
              <p className="text-sm font-semibold uppercase tracking-[0.4em] text-cyan-100/80">
                Launch window
              </p>
              <h1 className="max-w-4xl text-5xl font-black uppercase leading-[0.92] tracking-normal text-white sm:text-7xl lg:text-8xl">
                {config.title}
              </h1>
              <p className="max-w-2xl text-lg leading-8 text-zinc-300">
                Die Plattform wird zum Hackathon freigeschaltet. Der Kontrollraum zeigt den Countdown bis zum Startsignal am {eventDate}.
              </p>
            </div>
          </div>

          <div className="border border-white/15 bg-black/45 p-3 shadow-2xl shadow-red-950/30 backdrop-blur">
            <div className="border border-white/10 bg-zinc-950/80 p-4 sm:p-6">
              <div className="mb-5 flex items-center justify-between gap-3 border-b border-white/10 pb-4">
                <div>
                  <p className="text-xs font-bold uppercase tracking-[0.28em] text-zinc-400">Signal Countdown</p>
                  <p className="mt-1 font-mono text-sm text-cyan-100">{config.targetAt}</p>
                </div>
                <span className="h-3 w-3 animate-pulse rounded-full bg-red-400 shadow-[0_0_20px_rgba(248,113,113,0.9)]" />
              </div>

              {countdown.complete ? (
                <div className="py-16 text-center text-3xl font-black uppercase tracking-normal text-amber-100">
                  Hackathon gestartet
                </div>
              ) : (
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                  {COUNTDOWN_LABELS.map(([key, label]) => (
                    <div key={key} className="min-h-36 border border-white/10 bg-white/[0.045] p-4">
                      <div className="font-mono text-4xl font-black leading-none text-white sm:text-5xl">
                        {formatTime(countdown[key])}
                      </div>
                      <div className="mt-4 text-xs font-bold uppercase tracking-[0.24em] text-zinc-400">
                        {label}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}
