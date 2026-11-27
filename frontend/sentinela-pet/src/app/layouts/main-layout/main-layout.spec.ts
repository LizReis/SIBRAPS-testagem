import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { InicioService } from '../../services/inicio-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { MainLayout } from './main-layout';

describe('MainLayout', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MainLayout],
      providers: [
        provideRouter([]),
        { provide: UsuarioLogadoService, useValue: { obterUsuarioLogado: () => of({ idPublico: 'admin-teste', nome: 'Admin Teste', email: 'admin@test.local', tipoUsuario: 'ADMINISTRADOR', unidadeAtuacao: 'CAPS_I' }) } },
        { provide: InicioService, useValue: { buscarResumo: () => of({ totalPacientesAtivos: 12, totalAgendamentosHoje: 2, totalFaltasUltimosSeteDias: 1, totalPacientesAtencao: 3, totalNotificacoes: 2, pacientesPrioritarios: [] }) } },
      ],
    }).compileComponents();
  });

  it('atualiza estado acessível e fecha menu móvel pelo Escape', () => {
    const fixture = TestBed.createComponent(MainLayout); fixture.detectChanges();
    const c = fixture.componentInstance; c.isMobile.set(true); c.isSidebarOpen.set(true); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.menu-button').getAttribute('aria-expanded')).toBe('true');
    c.fecharMenuMovel(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.menu-button').getAttribute('aria-expanded')).toBe('false');
    expect(c.isSidebarOpen()).toBe(false);
  });
  it('should create', () => {
    const fixture = TestBed.createComponent(MainLayout);
    fixture.detectChanges();
    expect(fixture.componentInstance).toBeTruthy();
    expect(fixture.nativeElement.querySelector('app-header')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-sidebar')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('main router-outlet')).not.toBeNull();
    const aberta = fixture.componentInstance.isSidebarOpen();
    fixture.nativeElement.querySelector('.menu-button').click();
    expect(fixture.componentInstance.isSidebarOpen()).toBe(!aberta);
  });
});
