import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { LoginService } from '../services/login/login-service';

export const authGuard: CanActivateFn = () => {
  const router = inject(Router);
  const loginService = inject(LoginService);

  const token = loginService.getToken();

  if (token && token.trim() !== '') {
    return true;
  }

  loginService.removerToken();
  router.navigate(['/login']);
  return false;
};
