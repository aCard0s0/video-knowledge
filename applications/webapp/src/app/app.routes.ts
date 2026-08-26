import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'ingest' },
  {
    path: 'ingest',
    title: 'Ingest · VidIngest',
    loadComponent: () => import('./features/ingest/ingest').then((m) => m.Ingest),
  },
  {
    path: 'channels',
    title: 'Channels · VidIngest',
    loadComponent: () => import('./features/channels/channels').then((m) => m.Channels),
  },
  {
    path: 'channels/:channelId',
    title: 'Channel · VidIngest',
    loadComponent: () => import('./features/channels/channel-detail').then((m) => m.ChannelDetail),
  },
  {
    path: 'runs',
    title: 'Runs · VidIngest',
    loadComponent: () => import('./features/runs/runs').then((m) => m.Runs),
  },
  {
    path: 'runs/:runId',
    title: 'Run · VidIngest',
    loadComponent: () => import('./features/runs/run-detail').then((m) => m.RunDetail),
  },
  {
    path: 'videos',
    title: 'Videos · VidIngest',
    loadComponent: () => import('./features/videos/videos').then((m) => m.Videos),
  },
  {
    path: 'videos/:videoId',
    title: 'Video · VidIngest',
    loadComponent: () => import('./features/videos/video-detail').then((m) => m.VideoDetail),
  },
  {
    path: 'audit',
    title: 'Audit · VidIngest',
    loadComponent: () => import('./features/audit/audit').then((m) => m.Audit),
  },
  { path: '**', redirectTo: 'ingest' },
];
