import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { PacientePesquisa, PacienteService } from '../../services/paciente/paciente-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { Pacientes } from './pacientes';

describe('Pacientes', () => {
  const pesquisar = vi.fn(() => of<PacientePesquisa>({
    resumo: { pacientesAtivos: 0, emAtencao: 0, buscaAtiva: 0, semPresencaRecente: 0 },
    pacientes: [], totalRegistros: 0, totalPaginas: 0, paginaAtual: 0, tamanhoPagina: 10,
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

  it('apresenta uma falha de busca sem expor a resposta do servidor', async () => {
    const fixture = TestBed.createComponent(Pacientes);
    await fixture.whenStable();
    pesquisar.mockReturnValueOnce(throwError(() => new HttpErrorResponse({
      status: 503, error: { code: 'SERVICE_UNAVAILABLE', message: 'SQL interno sintético' },
    })));
    const component = fixture.componentInstance;
    component.termo = 'Paciente sintético';
    component.pesquisar();

    await vi.waitFor(() => expect(pesquisar).toHaveBeenCalledTimes(2));
    await fixture.whenStable();
    const alerta = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alerta.textContent).toContain('temporariamente indisponível');
    expect(alerta.textContent).not.toContain('SQL');
    expect(component.termo).toBe('Paciente sintético');
    expect(component.carregando).toBe(false);
    expect(component.dados).toBeUndefined();
  });

  it('aplica o resultado digitado e descarta a consulta anterior assim que o termo muda', async () => {
    const anterior = new Subject<PacientePesquisa>();
    pesquisar.mockReturnValueOnce(anterior.asObservable());
    const fixture = TestBed.createComponent(Pacientes);
    await fixture.whenStable();
    const component = fixture.componentInstance;
    component.termo = 'Nome sintético';
    component.pesquisar();
    const resumo = { pacientesAtivos: 0, emAtencao: 0, buscaAtiva: 0, semPresencaRecente: 0 };
    anterior.next({ resumo, pacientes: [], totalRegistros: 99, totalPaginas: 10, paginaAtual: 0, tamanhoPagina: 10 });
    expect(component.dados?.totalRegistros).not.toBe(99);

    pesquisar.mockReturnValueOnce(of({ resumo, pacientes: [], totalRegistros: 1, totalPaginas: 1, paginaAtual: 0, tamanhoPagina: 10 }));
    await vi.waitFor(() => expect(component.dados?.totalRegistros).toBe(1));
    expect(pesquisar).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'Nome sintético', page: 0 }));
  });

  it('remove resultados ao digitar uma busca incompleta', () => {
    const fixture = TestBed.createComponent(Pacientes);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    expect(component.dados).toBeDefined();
    component.termo = 'A';
    component.pesquisar();
    expect(component.dados).toBeUndefined();
    expect(component.buscaIncompleta).toBe(true);
    expect(pesquisar).toHaveBeenCalledOnce();
  });
});
