import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of, take } from 'rxjs';
import { interpretarErroHttp } from '../services/erro-http';
import { UsuarioLogadoService } from '../services/usuario-logado-service';

export const roleGuard: CanActivateFn = (route) => {
  const router = inject(Router);
  const usuarioService = inject(UsuarioLogadoService);
  const roles = route.data['roles'] as readonly string[] | undefined;
  if (!roles?.length) return true;
  return usuarioService.obterUsuarioLogado().pipe(
    take(1),
    map((usuario) => roles.includes(usuario.tipoUsuario) ? true : router.createUrlTree(['/inicio'])),
    catchError(erro => of(router.createUrlTree(
      interpretarErroHttp(erro).tipo === 'AUTHENTICATION_REQUIRED' ? ['/login'] : ['/inicio'],
    ))),
  );
};