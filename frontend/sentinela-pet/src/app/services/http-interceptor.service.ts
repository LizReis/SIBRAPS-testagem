import {
  HttpErrorResponse,
  HttpInterceptorFn,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

export const meuhttpInterceptor: HttpInterceptorFn = (request, next) => {
  const router = inject(Router);
  const token = localStorage.getItem('token');

  if (token) {
    request = request.clone({
      setHeaders: {
        Authorization: `Bearer ${token}`,
      },
    });
  }

  return next(request).pipe(
    catchError((erro: unknown) => {
      if (erro instanceof HttpErrorResponse) {
        if (erro.status === 401) {
          localStorage.removeItem('token');

          if (!router.url.includes('/login')) {
            router.navigate(['/login']);
          }
        } else {
          console.error('HTTP error:', erro);
        }
      } else {
        console.error('An error occurred:', erro);
      }

      return throwError(() => erro);
    }),
  );
};
