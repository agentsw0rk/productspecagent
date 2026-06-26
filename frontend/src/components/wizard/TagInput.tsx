"use client";

import { useState, type KeyboardEvent } from "react";
import { X } from "lucide-react";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

interface TagInputProps {
  tags: string[];
  onAdd: (tag: string) => void;
  onRemove: (tag: string) => void;
  placeholder?: string;
  color?: "green" | "red" | "default";
}

const COLOR_MAP = {
  green: { tag: "bg-[oklch(0.65_0.15_160)]/20 border-[oklch(0.65_0.15_160)]/40 text-[oklch(0.65_0.15_160)]", border: "border-[oklch(0.65_0.15_160)]/30" },
  red: { tag: "bg-destructive/20 border-destructive/40 text-destructive", border: "border-destructive/30" },
  default: { tag: "bg-muted text-muted-foreground border-border", border: "border-border" },
};

export function TagInput({ tags, onAdd, onRemove, placeholder = "Add tag...", color = "default" }: TagInputProps) {
  const [input, setInput] = useState("");
  const colors = COLOR_MAP[color];

  function handleKey(e: KeyboardEvent<HTMLInputElement>) {
    if ((e.key === "Enter" || e.key === ",") && input.trim()) {
      e.preventDefault();
      onAdd(input.trim());
      setInput("");
    }
  }

  return (
    <div className={cn("rounded-lg border p-2 min-h-[60px]", colors.border)}>
      <div className="flex flex-wrap gap-1.5 mb-1.5">
        {tags.map((tag) => (
          <span key={tag} className={cn("inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs", colors.tag)}>
            {tag}
            <button type="button" onClick={() => onRemove(tag)} className="hover:opacity-70">
              <X size={10} />
            </button>
          </span>
        ))}
      </div>
      <Input
        type="text"
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={handleKey}
        placeholder={tags.length === 0 ? placeholder : ""}
        className="border-0 bg-transparent dark:bg-transparent shadow-none focus-visible:ring-0 h-auto p-0 text-xs"
      />
    </div>
  );
}
