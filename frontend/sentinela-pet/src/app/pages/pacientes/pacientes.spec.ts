import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { PacienteService } from '../../services/paciente/paciente-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { Pacientes } from './pacientes';

describe('Pacientes', () => {
  const pesquisar = vi.fn(() => of({
    resumo: { pacientesAtivos: 0, emAtencao: 0, buscaAtiva: 0, semPresencaRecente: 0 },
    pacientes: [], totalRegistros: 0, totalPaginas: 0,
  }));

  beforeEach(async () => {
    pesquisar.mockClear();
    await TestBed.configureTestingModule({
      imports: [Pacientes],
      providers: [
        provideRouter([]),
        { provide: UsuarioLogadoService, useValue: { obterUsuarioLogado: () => of({ idPublico: 'admin-teste', nome: 'Admin Teste', email: 'admin@test.local', tipoUsuario: 'ADMINISTRADOR', unidadeAtuacao: 'CAPS_I' }) } },
        { provide: PacienteService, useValue: { pesquisar } },
      ],
    }).compileComponents();
  });

  it('should create e consultar a lista autorizada', () => {
    const fixture = TestBed.createComponent(Pacientes);
    fixture.detectChanges();
    expect(fixture.componentInstance).toBeTruthy();
    expect(pesquisar).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('Nenhum paciente encontrado.');
  });
});
