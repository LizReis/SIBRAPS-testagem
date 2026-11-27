import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { GrupoTerapeuticoPayload, GrupoTerapeuticoService, SessaoGrupoPayload } from '../../services/grupo-terapeutico-service';
import { DetalheGrupoTerapeutico } from './detalhe-grupo-terapeutico';

describe('DetalheGrupoTerapeutico', () => {
  const usuario = { idPublico: 'profissional-sintetico', nome: 'Profissional', email: '', tipoUsuario: 'PROFISSIONAL', unidadeAtuacao: 'CAPS_I' };
  let fixture: ComponentFixture<DetalheGrupoTerapeutico>;
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DetalheGrupoTerapeutico], providers: [provideHttpClient(), provideRouter([])] }).compileComponents();
    fixture = TestBed.createComponent(DetalheGrupoTerapeutico);
  });
  it('deve criar o componente', () => expect(fixture.componentInstance).toBeTruthy());

  it('mantém a confirmação e a justificativa quando o estado da escrita é incerto', () => {
    const componente = fixture.componentInstance;
    const grupos = TestBed.inject(GrupoTerapeuticoService);
    const atualizar = vi.spyOn(grupos, 'corrigirFrequencias').mockReturnValueOnce(throwError(() =>
      new HttpErrorResponse({ status: 503, error: { code: 'SERVICE_UNAVAILABLE' } })));
    const consultar = vi.spyOn(grupos, 'listarSessoes');
    componente.usuarioAtual = usuario;
    componente.sessao = {
      id: 2, version: 1, status: 'REALIZADA', responsavelAtendimentoId: usuario.idPublico, participantes: [{ pacienteId: 'sintetico', statusPresenca: 'FALTOU' }],
    } as SessaoGrupoPayload;
    componente.frequencias = { sintetico: 'PRESENTE' };
    componente.confirmandoCorrecao = true;
    componente.justificativaCorrecao = 'Motivo sintético da correção.';

    componente.salvarCorrecoes();

    expect(componente.erro).toContain('Confira o estado atual');
    expect(componente.confirmandoCorrecao).toBe(true);
    expect(componente.justificativaCorrecao).toBe('Motivo sintético da correção.');
    expect(componente.frequencias).toEqual({ sintetico: 'PRESENTE' });
    expect(atualizar).toHaveBeenCalledOnce();
    expect(consultar).not.toHaveBeenCalled();
  });

  it('usa a versão devolvida ao salvar provisórias de participantes diferentes', () => {
    const componente = fixture.componentInstance;
    componente.usuarioAtual = usuario;
    componente.grupo = { coordenadorId: usuario.idPublico } as GrupoTerapeuticoPayload;
    const participantes = [
      { pacienteId: 'um', nomePaciente: 'Pessoa 1', statusPresenca: 'NAO_REGISTRADA' as const },
      { pacienteId: 'dois', nomePaciente: 'Pessoa 2', statusPresenca: 'NAO_REGISTRADA' as const },
    ];
    const sessao = { id: 2, version: 1, status: 'AGENDADA', statusExibicao: 'EM_ANDAMENTO',
      profissionalDesignadoId: usuario.idPublico, participantes } as SessaoGrupoPayload;
    const primeira = { ...sessao, version: 2, participantes: [
      { ...participantes[0], statusPresenca: 'PRESENTE' as const }, participantes[1],
    ] };
    componente.sessao = sessao;
    componente.frequencias = { um: 'PRESENTE', dois: 'FALTOU' };
    const salvar = vi.spyOn(TestBed.inject(GrupoTerapeuticoService), 'registrarProvisoria')
      .mockReturnValueOnce(of(primeira)).mockReturnValueOnce(of({ ...primeira, version: 3 }));

    componente.salvarProvisoria(participantes[0]);
    expect(componente.labelFrequencia(primeira.participantes[0])).toBe('Provisória salva: Presente');
    expect(componente.temRascunho(participantes[1])).toBe(true);
    componente.salvarProvisoria(participantes[1]);

    expect(salvar).toHaveBeenNthCalledWith(1, 2, 'um', 'PRESENTE', 1);
    expect(salvar).toHaveBeenNthCalledWith(2, 2, 'dois', 'FALTOU', 2);
    expect(componente.sessao?.status).toBe('AGENDADA');
  });

  it('bloqueia confirmação, cancelamento e provisória com autoria pendente, inclusive para administrador', () => {
    const componente = fixture.componentInstance;
    componente.usuarioAtual = { ...usuario, tipoUsuario: 'ADMINISTRADOR' };
    componente.grupo = { coordenadorId: usuario.idPublico } as GrupoTerapeuticoPayload;
    componente.sessao = { id: 2, version: 1, status: 'AGENDADA', statusExibicao: 'EM_ANDAMENTO',
      profissionalDesignadoId: usuario.idPublico, responsabilidadePendente: true, participantes: [] } as unknown as SessaoGrupoPayload;
    expect(componente.podeCancelarSessao).toBe(false);
    expect(componente.podeConfirmarRealizacao).toBe(false);
    expect(componente.podeGravarProvisoria).toBe(false);
  });
});
