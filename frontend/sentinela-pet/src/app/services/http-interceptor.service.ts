import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, mergeMap, throwError } from 'rxjs';
import { interpretarErroHttp } from './erro-http';
import { LoginService } from './login/login-service';

async function lerErroJson(erro: HttpErrorResponse): Promise<HttpErrorResponse> {
  const blob = erro.error;
  if (!(blob instanceof Blob) || blob.size > 64_000
      || !/^application\/(?:[\w.+-]+\+)?json\b/i.test(blob.type || erro.headers.get('Content-Type') || '')) {
    return erro;
  }
  try {
    return new HttpErrorResponse({
      error: JSON.parse(await blob.text()),
      headers: erro.headers,
      status: erro.status,
      statusText: erro.statusText,
      url: erro.url ?? undefined,
    });
  } catch {
    return erro;
  }
}

export const meuhttpInterceptor: HttpInterceptorFn = (request, next) => {
  const router = inject(Router);
  const sessao = inject(LoginService);
  const token = sessao.getToken();
  const login = request.url === `${LoginService.BASE_PATH}/api/auth/login`;

  if (token && !login) {
    request = request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }

  return next(request).pipe(
    catchError((erro: unknown) => {
      if (!(erro instanceof HttpErrorResponse)) return throwError(() => erro);

      const propagar = (resposta: HttpErrorResponse) => {
        if (!login && interpretarErroHttp(resposta).tipo === 'AUTHENTICATION_REQUIRED'
            && token === sessao.getToken()) {
          sessao.removerToken();
          if (!/^\/login(?:[?#]|$)/.test(router.url)) void router.navigate(['/login']);
        }
        return throwError(() => resposta);
      };

      return erro.error instanceof Blob
        ? from(lerErroJson(erro)).pipe(mergeMap(propagar))
        : propagar(erro);
    }),
  );
};
