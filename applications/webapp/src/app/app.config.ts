import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideHttpClient, withFetch } from '@angular/common/http';

import { routes } from './app.routes';
import { provideApi } from './api/generated';
import { API_BASE } from './core/api-base';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'top' })),
    provideHttpClient(withFetch()),
    // Same-origin in production (the build is served from the server's classpath:/static under
    // the /vidingest context path); the dev server proxies /vidingest to :8051. Either way the
    // client only ever emits relative URLs, so no CORS preflight and no environment file.
    provideApi(API_BASE),
  ],
};
