"use client";
import { ClassicPreset } from "rete";
import { Presets } from "rete-react-plugin";
import type { ClassicScheme, RenderEmit } from "rete-react-plugin";

const { RefSocket, useConnection } = Presets.classic;
import type { FeatureScope } from "@/lib/api";

const socketHitboxClass =
  "group inline-flex h-6 w-6 items-center justify-center rounded-full cursor-crosshair";

export class FeatureRNode extends ClassicPreset.Node {
  width = 220;
  height = 120;
  constructor(
    public featureId: string,
    label: string,
    public scopes: FeatureScope[] = [],
    public description: string = "",
  ) {
    super(label);
  }
}

interface Props {
  data: FeatureRNode;
  emit: RenderEmit<ClassicScheme>;
}

// The custom node REPLACES the default preset template entirely, which means
// input/output sockets must be rendered by hand — otherwise the connection
// plugin can't locate them and users see a node without any drag handles.
export function FeatureNodeComponent({ data, emit }: Props) {
  const hasFrontend = data.scopes.includes("FRONTEND");
  const hasBackend = data.scopes.includes("BACKEND");
  const isActive = data.scopes.includes("ACTIVE" as FeatureScope);
  const visibleScopes = data.scopes.filter((scope) => scope !== ("ACTIVE" as FeatureScope));
  const isCore = visibleScopes.length === 0;

  const inputs = Object.entries(data.inputs);
  const outputs = Object.entries(data.outputs);

  return (
    <div
      className={`relative rounded-lg border bg-card shadow-sm ${
        isActive ? "border-primary/70 bg-primary/10 ring-2 ring-primary/35" : ""
      }`}
      style={{ width: data.width, minHeight: data.height }}
      data-testid="node"
    >
      <div className="px-3 py-2 border-b flex items-center justify-between gap-2">
        <span className="text-sm font-medium truncate" data-testid="title">
          {data.label}
        </span>
        <div className="flex items-center gap-1">
          {isActive && <Badge label="Aktiv" color="primary" />}
          {isCore && <Badge label="Core" color="neutral" />}
          {hasFrontend && <Badge label="FE" color="cyan" />}
          {hasBackend && <Badge label="BE" color="violet" />}
        </div>
      </div>

      {data.description && (
        <div
          className="px-3 py-2 text-xs text-muted-foreground line-clamp-3 leading-snug"
          data-testid="description"
          title={data.description}
        >
          {data.description}
        </div>
      )}

      {inputs.map(([key, input]) =>
        input ? (
          <div
            key={`in-${key}`}
            className="absolute left-0 top-1/2 -translate-x-1/2 -translate-y-1/2"
            data-testid={`input-${key}`}
          >
            <RefSocket
              name={`input-socket ${socketHitboxClass}`}
              side="input"
              socketKey={key}
              nodeId={data.id}
              emit={emit}
              payload={input.socket}
            />
          </div>
        ) : null,
      )}

      {outputs.map(([key, output]) =>
        output ? (
          <div
            key={`out-${key}`}
            className="absolute right-0 top-1/2 translate-x-1/2 -translate-y-1/2"
            data-testid={`output-${key}`}
          >
            <RefSocket
              name={`output-socket ${socketHitboxClass}`}
              side="output"
              socketKey={key}
              nodeId={data.id}
              emit={emit}
              payload={output.socket}
            />
          </div>
        ) : null,
      )}
    </div>
  );
}

function Badge({ label, color }: { label: string; color: "cyan" | "violet" | "neutral" | "primary" }) {
  const classes = color === "cyan"
    ? "bg-cyan-500/15 text-cyan-300"
    : color === "violet"
    ? "bg-violet-500/15 text-violet-300"
    : color === "primary"
    ? "bg-primary text-primary-foreground"
    : "bg-muted text-muted-foreground";
  return <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${classes}`}>{label}</span>;
}

// Custom socket — replaces rete's steelblue/white-border default so it matches
// the app's primary accent and sits flush against the card background.
export function FeatureSocketComponent() {
  return (
    <div
      className="pointer-events-none h-3.5 w-3.5 rounded-full bg-primary ring-2 ring-background shadow-sm transition-all group-hover:ring-primary/40 group-hover:scale-110"
      data-testid="socket"
    />
  );
}

type FeatureConnectionPayload = ClassicScheme["Connection"] & { isLoop?: boolean };

// Custom connection — thinner stroke in primary color instead of the default
// 5px steelblue; loop edges render dashed so they read as self-references.
export function FeatureConnectionComponent({ data }: { data: FeatureConnectionPayload }) {
  const { path } = useConnection();
  if (!path) return null;
  return (
    <svg
      className="absolute overflow-visible"
      style={{ width: 9999, height: 9999, pointerEvents: "none" }}
      data-testid="connection"
    >
      <path
        d={path}
        fill="none"
        stroke="var(--color-primary)"
        strokeWidth={2}
        strokeOpacity={0.85}
        strokeLinecap="round"
        strokeDasharray={data.isLoop ? "6 4" : undefined}
        style={{ pointerEvents: "auto" }}
      />
    </svg>
  );
}
