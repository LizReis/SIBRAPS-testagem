import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { InicioService } from '../../services/inicio-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { Header } from './header';

describe('Header', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Header],
      providers: [
        { provide: UsuarioLogadoService, useValue: { obterUsuarioLogado: () => of({ idPublico: 'admin-teste', nome: 'Admin Teste', email: 'admin@test.local', tipoUsuario: 'ADMINISTRADOR', unidadeAtuacao: 'CAPS_I' }) } },
        { provide: InicioService, useValue: { buscarResumo: () => of({ totalPacientesAtivos: 12, totalAgendamentosHoje: 2, totalFaltasUltimosSeteDias: 1, totalPacientesAtencao: 3, totalNotificacoes: 2, pacientesPrioritarios: [] }) } },
      ],
    }).compileComponents();
  });

  it('should create e renderizar usuário e notificações simulados', () => {
    const fixture = TestBed.createComponent(Header);
    fixture.detectChanges();
    expect(fixture.componentInstance).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.profile-info')?.textContent).toContain('Admin Teste');
    expect(fixture.nativeElement.querySelector('.notification-badge')?.textContent).toContain('2');
  });
});
