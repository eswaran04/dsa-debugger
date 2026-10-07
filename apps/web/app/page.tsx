"use client";

import dynamic from "next/dynamic";
import { Panel, PanelGroup, PanelResizeHandle } from "react-resizable-panels";
import { ArrayViz } from "@/components/ArrayViz";
import { CallStackPanel } from "@/components/CallStackPanel";
import { ConsolePanel } from "@/components/ConsolePanel";
import { DebugToolbar } from "@/components/DebugToolbar";
import { Header } from "@/components/Header";
import { InputPanel } from "@/components/InputPanel";
import { ResultPanel } from "@/components/ResultPanel";
import { StatusBanner } from "@/components/StatusBanner";
import { Timeline } from "@/components/Timeline";
import { VariablesPanel } from "@/components/VariablesPanel";

// Monaco touches window; load it client-side only.
const Editor = dynamic(() => import("@/components/Editor").then((m) => m.Editor), { ssr: false });

const H = () => <PanelResizeHandle className="w-1 bg-line/60 transition-colors hover:bg-accent/50" />;
const V = () => <PanelResizeHandle className="h-1 bg-line/60 transition-colors hover:bg-accent/50" />;

export default function Home() {
  return (
    <div className="flex h-screen flex-col overflow-hidden">
      <Header />
      <PanelGroup direction="horizontal" autoSaveId="dsa-h" className="flex-1">
        <Panel defaultSize={58} minSize={30}>
          <PanelGroup direction="vertical" autoSaveId="dsa-left">
            <Panel defaultSize={68} minSize={25} className="flex flex-col bg-[#0b0f17]">
              <StatusBanner />
              <div className="min-h-0 flex-1 pt-2">
                <Editor />
              </div>
            </Panel>
            <V />
            <Panel defaultSize={32} minSize={15} className="flex flex-col">
              <div className="flex items-center gap-4 border-b border-line bg-panel px-3 py-1.5">
                <DebugToolbar />
                <Timeline />
              </div>
              <PanelGroup direction="horizontal" autoSaveId="dsa-bottom" className="flex-1">
                <Panel defaultSize={34} minSize={15} className="panel">
                  <InputPanel />
                </Panel>
                <H />
                <Panel defaultSize={33} minSize={15} className="panel">
                  <ConsolePanel />
                </Panel>
                <H />
                <Panel defaultSize={33} minSize={15} className="panel">
                  <ResultPanel />
                </Panel>
              </PanelGroup>
            </Panel>
          </PanelGroup>
        </Panel>
        <H />
        <Panel defaultSize={42} minSize={22}>
          <PanelGroup direction="vertical" autoSaveId="dsa-right">
            <Panel defaultSize={38} minSize={15} className="panel">
              <VariablesPanel />
            </Panel>
            <V />
            <Panel defaultSize={22} minSize={10} className="panel">
              <CallStackPanel />
            </Panel>
            <V />
            <Panel defaultSize={40} minSize={15} className="panel">
              <ArrayViz />
            </Panel>
          </PanelGroup>
        </Panel>
      </PanelGroup>
    </div>
  );
}
