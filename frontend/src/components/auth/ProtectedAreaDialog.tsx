"use client";

import { ShieldAlert } from "lucide-react";
import { useRouter } from "next/navigation";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";

interface ProtectedAreaDialogProps {
  open: boolean;
}

export function ProtectedAreaDialog({ open }: ProtectedAreaDialogProps) {
  const router = useRouter();

  return (
    <Dialog open={open}>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <div className="mb-1 flex size-9 items-center justify-center rounded-md bg-destructive/10 text-destructive">
            <ShieldAlert className="size-5" aria-hidden="true" />
          </div>
          <DialogTitle>Bereich geschützt</DialogTitle>
          <DialogDescription>
            Du hast keine Berechtigung, diesen Administrationsbereich zu öffnen.
            Melde dich mit einem Admin-Konto an oder bitte einen Administrator um Freigabe.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button type="button" onClick={() => router.push("/projects")}>
            Zur Projektübersicht
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
