import { HttpErrorResponse } from '@angular/common/http';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { throwError } from 'rxjs';
import { UsuarioLogadoService } from '../services/usuario-logado-service';
import { roleGuard } from './role-guard';

@Component({ template: '' })
class PaginaDeTeste {}

describe('roleGuard e recusas da API', () => {
  it.each([
    [401, 'AUTHENTICATION_REQUIRED', '/login'],
    [403, 'ACCESS_DENIED', '/inicio'],
    [503, 'SERVICE_UNAVAILABLE', '/inicio'],
  ])('trata HTTP %d sem confundir falta de permissão ou serviço com sessão inválida', async (status, code, destino) => {
    TestBed.configureTestingModule({ providers: [
      provideRouter([
        { path: 'restrita', component: PaginaDeTeste, canActivate: [roleGuard], data: { roles: ['ADMINISTRADOR'] } },
        { path: 'login', component: PaginaDeTeste },
        { path: 'inicio', component: PaginaDeTeste },
      ]),
      {
        provide: UsuarioLogadoService,
        useValue: { obterUsuarioLogado: () => throwError(() => new HttpErrorResponse({ status, error: { code } })) },
      },
    ] });
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/restrita');
    expect(router.url).toBe(destino);
  });
});
