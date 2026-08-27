import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withViewTransitions,
} from '@angular/router';
import { provideHttpClient, withFetch } from '@angular/common/http';

import { routes } from './app.routes';
import { provideApi } from './api/generated';
import { API_BASE } from './core/api-base';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Route changes cross-fade through the browser's own View Transitions API — no animation
    // library, and a browser without the API navigates exactly as before. Only <main> carries a
    // `view-transition-name` (styles.scss), so the rail never animates with the page it frames.
    // The first navigation is skipped: that one paints the shell, and animating a boot reads as a
    // slow load rather than a transition.
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
      withViewTransitions({ skipInitialTransition: true }),
    ),
    provideHttpClient(withFetch()),
    // Same-origin in production (the build is served from the server's classpath:/static under
    // the /vidingest context path); the dev server proxies /vidingest to :8051. Either way the
    // client only ever emits relative URLs, so no CORS preflight and no environment file.
    provideApi(API_BASE),
  ],
};
