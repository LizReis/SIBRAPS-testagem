import { routes } from './app.routes';
import { authGuard } from './auth/auth-guard';
import { roleGuard } from './auth/role-guard';

describe('Rotas carregadas sob demanda', () => {
  const principal = routes.find(r => r.canActivate?.includes(authGuard));
  it('carrega Relatórios mantendo a proteção do layout autenticado', async () => {
    const rota = principal?.children?.find(r => r.path === 'relatorios');
    expect(rota?.component).toBeUndefined();
    expect(rota?.loadComponent).toBeDefined();
    expect(await rota?.loadComponent?.()).toBe((await import('./pages/relatorios/relatorios')).Relatorios);
  });
  it('carrega Pendências clínicas mantendo a restrição administrativa', async () => {
    const rota = principal?.children?.find(r => r.path === 'pendencias-clinicas');
    expect(rota?.component).toBeUndefined();
    expect(rota?.canActivate).toEqual([roleGuard]);
    expect(rota?.data?.['roles']).toEqual(['ADMINISTRADOR']);
    expect(await rota?.loadComponent?.()).toBe((await import('./pages/pendencias-clinicas/pendencias-clinicas')).PendenciasClinicas);
  });
});
