import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { LoginService } from '../services/login/login-service';

export const loginGuard: CanActivateFn = () => {
  const router = inject(Router);
  const loginService = inject(LoginService);

  const token = loginService.getToken();

  if (token) {
    router.navigate(['/inicio']);
    return false;
  }

  return true;
};
