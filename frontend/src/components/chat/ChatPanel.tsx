"use client";

import { useState, useRef, useEffect, type KeyboardEvent } from "react";
import { Send, Loader2, Bot } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { ChatMessage } from "./ChatMessage";
import { useProjectStore } from "@/lib/stores/project-store";

interface ChatPanelProps {
  projectId: string;
  disableComposer?: boolean;
}

export function ChatPanel({ projectId, disableComposer = false }: ChatPanelProps) {
  const { messages, chatSending, sendMessage } = useProjectStore();
  const [input, setInput] = useState("");
  const bottomRef = useRef<HTMLDivElement>(null);
  const composerDisabled = disableComposer || chatSending;

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  async function handleSend() {
    const text = input.trim();
    if (!text || composerDisabled) return;
    setInput("");
    await sendMessage(projectId, text);
  }

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2 border-b px-4 py-3">
        <div className="flex h-7 w-7 items-center justify-center rounded-full bg-primary/10 text-primary">
          <Bot size={15} />
        </div>
        <div>
          <span className="text-sm font-semibold text-foreground">Spec Agent</span>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-4">
        {messages.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-2 text-center text-muted-foreground">
            <Bot size={32} className="opacity-30" />
            <p className="text-sm">
              {disableComposer
                ? "Der Wizard steuert den Agenten. Antworten erscheinen hier automatisch."
                : "Sag kurz Hallo. Ich fuehre dich durch die naechsten Spec-Schritte."}
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {messages.map((msg) => (
              <ChatMessage key={msg.id} message={msg} />
            ))}
            {chatSending && (
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <Loader2 size={12} className="animate-spin" />
                <span>Agent denkt nach...</span>
              </div>
            )}
            <div ref={bottomRef} />
          </div>
        )}
      </div>

      <div className="p-3">
        <div className="flex items-end gap-2 rounded-lg border border-border bg-background p-2">
          <Textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={disableComposer ? "Nachrichten werden vom Wizard erzeugt." : "Nachricht an den Agenten... (Enter zum Senden)"}
            rows={1}
            disabled={composerDisabled}
            className="flex-1 resize-none border-0 bg-transparent dark:bg-transparent shadow-none focus-visible:ring-0 max-h-32 min-h-[24px] px-0 py-0"
          />
          <Button
            size="icon-sm"
            onClick={handleSend}
            disabled={!input.trim() || composerDisabled}
            aria-label={disableComposer ? "Manueller Chat ist deaktiviert" : "Nachricht senden"}
            title={disableComposer ? "Manueller Chat ist deaktiviert" : "Nachricht senden"}
            className="shrink-0"
          >
            {chatSending ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}
          </Button>
        </div>
      </div>
    </div>
  );
}
