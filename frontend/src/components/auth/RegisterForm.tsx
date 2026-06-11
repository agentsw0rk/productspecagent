"use client";

import { useState } from "react";
import Link from "next/link";
import { useAuthStore } from "@/lib/stores/auth-store";
import { redirectAfterAuth } from "@/lib/auth-navigation";
import { ApiError } from "@/lib/api";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";

export function RegisterForm() {
  const register = useAuthStore((s) => s.register);

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register(email, password);
      redirectAfterAuth("/projects");
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError("Email bereits registriert");
      } else if (err instanceof ApiError && err.status === 400) {
        setError("Ungültige Eingabe (Email-Format oder Passwort < 8 Zeichen)");
      } else {
        setError("Registrierung fehlgeschlagen");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="w-full max-w-sm space-y-4 rounded-lg border bg-card p-6">
      <h1 className="text-xl font-semibold">Konto anlegen</h1>

      <div className="space-y-2">
        <Label htmlFor="email">Email</Label>
        <Input id="email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" />
      </div>

      <div className="space-y-2">
        <Label htmlFor="password">Passwort (min. 8 Zeichen)</Label>
        <Input id="password" type="password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
      </div>

      {error && <p className="text-sm text-red-500">{error}</p>}

      <Button type="submit" disabled={submitting} className="w-full">
        {submitting ? "..." : "Registrieren"}
      </Button>

      <p className="text-sm text-muted-foreground">
        Schon ein Konto? <Link href="/login" className="underline">Anmelden →</Link>
      </p>
    </form>
  );
}
