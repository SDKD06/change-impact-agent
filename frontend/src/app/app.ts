import { Component, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ImpactService, ImpactReport } from './impact.service';

interface LaidOutNode { id: string; label: string; hop: number; x: number; y: number; color: string; }
interface LaidOutEdge { x1: number; y1: number; x2: number; y2: number; color: string; }

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  method = '';
  examples = ['swap', 'sort', 'getNode', 'partition'];

  connected = signal(false);
  loading = signal(false);
  loadingMsg = signal('');
  report = signal<ImpactReport | null>(null);
  error = signal<string | null>(null);

  reportLoading = signal(false);
  narrative = signal<string>('');

  private readonly W = 720;
  private readonly H = 460;
  private hopColors: Record<number, string> = {
    0: '#3b82f6', 1: '#3b82f6', 2: '#60a5fa', 3: '#fbbf24', 4: '#f87171',
  };
  private loadingMessages = [
    'Resolving method…', 'Tracing the call graph…', 'Computing blast radius…', 'Laying out the graph…',
  ];
  private msgTimer: any;

  constructor(private impact: ImpactService) {}

  ngOnInit(): void {
    this.impact.analyze('__ping__').subscribe({
      next: () => this.connected.set(true),
      error: (e) => this.connected.set(e.status !== 0),
    });
  }

  useExample(ex: string): void { this.method = ex; this.run(); }

  run(): void {
    const name = this.method.trim();
    if (!name) return;
    this.error.set(null);
    this.report.set(null);
    this.narrative.set('');
    this.loading.set(true);
    this.cycleMessages();

    this.impact.analyze(name).subscribe({
      next: (res) => {
        this.stopMessages();
        this.loading.set(false);
        if (!res.found) {
          this.error.set(`No method named "${name}" was found in the codebase. Try one of the examples.`);
          return;
        }
        this.report.set(res);
      },
      error: (e) => {
        this.stopMessages();
        this.loading.set(false);
        this.error.set(e.status === 0
          ? 'Cannot reach the backend. Is the Spring Boot app running on port 8080?'
          : 'Something went wrong analyzing that method.');
      },
    });
  }

  generateNarrative(): void {
    const r = this.report();
    if (!r) return;
    this.reportLoading.set(true);
    this.impact.analyzeWithReport(r.query).subscribe({
      next: (res) => { this.reportLoading.set(false); this.narrative.set(res.agentReport); },
      error: () => { this.reportLoading.set(false); this.narrative.set('Could not generate the written report (the local model may be busy).'); },
    });
  }

  riskColor(): string {
    switch (this.report()?.riskLevel) {
      case 'High': return '#f87171';
      case 'Medium': return '#fbbf24';
      default: return '#4ade80';
    }
  }

  // ---- force-directed layout (pure TS, deterministic) ----
  private layout = computed<{ nodes: LaidOutNode[]; edges: LaidOutEdge[] }>(() => {
    const g = this.report()?.graph;
    if (!g || !g.nodes.length) return { nodes: [], edges: [] };

    const cx = this.W / 2, cy = this.H / 2;
    type P = { x: number; y: number; vx: number; vy: number; hop: number };
    const pos = new Map<string, P>();

    // deterministic seed: root centered, others on rings by hop
    const hopCounts = new Map<number, number>();
    g.nodes.forEach(n => hopCounts.set(n.hop, (hopCounts.get(n.hop) || 0) + 1));
    const hopSeen = new Map<number, number>();
    g.nodes.forEach(n => {
      if (n.hop === 0) { pos.set(n.id, { x: cx, y: cy, vx: 0, vy: 0, hop: 0 }); return; }
      const count = hopCounts.get(n.hop)!;
      const i = hopSeen.get(n.hop) || 0; hopSeen.set(n.hop, i + 1);
      const angle = (i / count) * Math.PI * 2 + n.hop * 0.7;
      const r = n.hop * 95;
      pos.set(n.id, { x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle), vx: 0, vy: 0, hop: n.hop });
    });

    const ids = [...pos.keys()];
    const edges = g.edges.filter(e => pos.has(e.from) && pos.has(e.to));
    const REP = 11000, SPRING = 0.02, LEN = 100, CENTER = 0.004, DAMP = 0.85;
    const iters = ids.length > 60 ? 90 : 220;

    for (let it = 0; it < iters; it++) {
      for (let a = 0; a < ids.length; a++) {
        const pa = pos.get(ids[a])!;
        for (let b = a + 1; b < ids.length; b++) {
          const pb = pos.get(ids[b])!;
          let dx = pa.x - pb.x, dy = pa.y - pb.y;
          let d2 = dx * dx + dy * dy || 0.01;
          let d = Math.sqrt(d2);
          const f = REP / d2;
          const fx = (dx / d) * f, fy = (dy / d) * f;
          pa.vx += fx; pa.vy += fy; pb.vx -= fx; pb.vy -= fy;
        }
      }
      for (const e of edges) {
        const pa = pos.get(e.from)!, pb = pos.get(e.to)!;
        let dx = pb.x - pa.x, dy = pb.y - pa.y;
        let d = Math.sqrt(dx * dx + dy * dy) || 0.01;
        const f = SPRING * (d - LEN);
        const fx = (dx / d) * f, fy = (dy / d) * f;
        pa.vx += fx; pa.vy += fy; pb.vx -= fx; pb.vy -= fy;
      }
      for (const id of ids) {
        const p = pos.get(id)!;
        if (p.hop === 0) { p.x = cx; p.y = cy; p.vx = 0; p.vy = 0; continue; }
        p.vx += (cx - p.x) * CENTER; p.vy += (cy - p.y) * CENTER;
        p.vx *= DAMP; p.vy *= DAMP;
        p.x += p.vx; p.y += p.vy;
        p.x = Math.max(40, Math.min(this.W - 40, p.x));
        p.y = Math.max(34, Math.min(this.H - 34, p.y));
      }
    }

    const nodes: LaidOutNode[] = g.nodes.map(n => {
      const p = pos.get(n.id)!;
      return { id: n.id, label: n.label, hop: n.hop, x: p.x, y: p.y, color: this.hopColors[Math.min(n.hop, 4)] };
    });
    const laidEdges: LaidOutEdge[] = edges.map(e => {
      const a = pos.get(e.from)!, b = pos.get(e.to)!;
      return { x1: a.x, y1: a.y, x2: b.x, y2: b.y, color: this.hopColors[Math.min(Math.max(a.hop, b.hop), 4)] };
    });
    return { nodes, edges: laidEdges };
  });

  layoutNodes = computed(() => this.layout().nodes);
  layoutEdges = computed(() => this.layout().edges);

  // gentle curve for each edge
  edgePath(e: LaidOutEdge): string {
    const mx = (e.x1 + e.x2) / 2, my = (e.y1 + e.y2) / 2;
    const dx = e.x2 - e.x1, dy = e.y2 - e.y1;
    const len = Math.sqrt(dx * dx + dy * dy) || 1;
    const off = 16;
    const cxp = mx + (-dy / len) * off, cyp = my + (dx / len) * off;
    return `M ${e.x1} ${e.y1} Q ${cxp} ${cyp} ${e.x2} ${e.y2}`;
  }

  private cycleMessages(): void {
    let i = 0;
    this.loadingMsg.set(this.loadingMessages[0]);
    this.msgTimer = setInterval(() => {
      i = (i + 1) % this.loadingMessages.length;
      this.loadingMsg.set(this.loadingMessages[i]);
    }, 1500);
  }
  private stopMessages(): void { if (this.msgTimer) clearInterval(this.msgTimer); }
}