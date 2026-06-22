import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface AffectedMethod { name: string; file: string; line: number; hop: number; }
export interface GraphNode { id: string; label: string; hop: number; }
export interface GraphEdge { from: string; to: string; }

export interface ImpactReport {
  query: string;
  resolvedId: string | null;
  found: boolean;
  riskLevel: 'Low' | 'Medium' | 'High' | null;
  summary: string;
  stats: { methodsAffected: number; filesTouched: number; maxDepth: number; directCallers: number; };
  directBreakage: AffectedMethod[];
  behavioralRisk: AffectedMethod[];
  graph: { nodes: GraphNode[]; edges: GraphEdge[]; };
  agentReport: string;
}

@Injectable({ providedIn: 'root' })
export class ImpactService {
  private readonly baseUrl = 'http://localhost:8080/api';

  constructor(private http: HttpClient) {}

  /** Fast: structured data only, no slow LLM. */
  analyze(method: string): Observable<ImpactReport> {
    return this.http.post<ImpactReport>(
      `${this.baseUrl}/impact?method=${encodeURIComponent(method)}`, {});
  }

  /** Slow: also generates the AI written report. */
  analyzeWithReport(method: string): Observable<ImpactReport> {
    return this.http.post<ImpactReport>(
      `${this.baseUrl}/impact?method=${encodeURIComponent(method)}&withReport=true`, {});
  }
}
