import { HttpClient } from '@angular/common/http';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { provideRouter } from '@angular/router';
import { PainelService } from '../../services/painel-service';
import { Painel } from './painel';

const payload = {
  totalPacientesAtivos: 3,
  distribuicaoClassificacao: { verdes: 1, amarelos: 1, vermelhos: 1, percentualVerdes: 33.3, percentualAmarelos: 33.3, percentualVermelhos: 33.3 },
  evolucao: [{ mes: '2026-09', rotulo: 'Set/26', verdes: 1, amarelos: 1, vermelhos: 1, disponivel: true }],
  pacientesPrioritarios: [{ idPublico: 'real-id', nome: 'Paciente Real', idade: 30, classificacaoRisco: 'VERMELHO' as const, quantidadeFaltas: 2, acaoNecessaria: 'Acompanhamento prioritário' }],
  unidadesDisponiveis: [{ valor: 'CAPS:CAPS_II', nome: 'CAPS II' }], historicoDisponivel: true,
};

describe('Painel', () => {
  let component: Painel; let fixture: ComponentFixture<Painel>; let service: { buscarResumo: ReturnType<typeof vi.fn> };
  beforeEach(async () => {
    service = { buscarResumo: vi.fn().mockReturnValue(of(payload)) };
    await TestBed.configureTestingModule({ imports: [Painel], providers: [provideRouter([]),
      { provide: PainelService, useValue: service },
      { provide: HttpClient, useValue: { get: () => of({ alertas: [], estadoNaoConfirmado: 0, selecaoIndeterminada: 0 }) } },
      { provide: UsuarioLogadoService, useValue: { obterUsuarioLogado: () => of({ tipoUsuario: 'PROFISSIONAL' }) } },
    ] }).compileComponents();
    fixture = TestBed.createComponent(Painel); component = fixture.componentInstance;
  });
  it('cria, mostra loading e renderiza totais, gráficos e prioritário vindos da API', () => {
    expect(component).toBeTruthy(); expect(component.carregando).toBe(true);
    fixture.detectChanges();
    expect(component.dados?.totalPacientesAtivos).toBe(3);
    expect(component.pontos('verdes')).not.toBe('');
    expect(fixture.nativeElement.textContent).toContain('Paciente Real');
    expect(fixture.nativeElement.querySelector('a[href="/pacientes/detalhes/real-id"]')).toBeTruthy();
  });
  it('distingue cobertura ausente, indicador indisponível e zero comprovado', () => {
    const nulo = { mes: '2026-08', rotulo: 'Ago/26', verdes: null, amarelos: null, vermelhos: null, disponivel: false };
    expect(component.tooltip({ ...nulo, situacao: 'SEM_COBERTURA' })).toContain('Sem cobertura certificada');
    expect(component.tooltip({ ...nulo, situacao: 'INDISPONIVEL' })).toContain('Indicadores indisponíveis');
    expect(component.tooltip({ ...nulo, disponivel: true, situacao: 'COMPROVADO',
      verdes: 0, amarelos: 0, vermelhos: 0 })).toContain('Regular 0');
  });
  it('aplica filtros em uma única nova requisição', () => {
    fixture.detectChanges(); component.rascunhoTipo = 'INDIVIDUAL'; component.rascunhoRua = 'true'; component.aplicarMaisFiltros();
    expect(service.buscarResumo).toHaveBeenCalledTimes(2);
    expect(service.buscarResumo).toHaveBeenLastCalledWith(expect.objectContaining({ tipoAcompanhamento: 'INDIVIDUAL', situacaoRua: true }));
  });
  it('mostra estado vazio', () => {
    service.buscarResumo.mockReturnValue(of({ ...payload, pacientesPrioritarios: [] })); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nenhum paciente prioritário confirmado');
  });
  it('mostra erro e permite tentar novamente', () => {
    service.buscarResumo.mockReturnValue(throwError(() => new Error('falha'))); fixture.detectChanges();
    expect(component.erro).toBe(true); expect(fixture.nativeElement.textContent).toContain('Não foi possível carregar');
  });
  it.each([
    [4, 3, 2],
    [90, 8, 2],
    [0, 5, 5],
    [1, 0, 0],
  ])('mantém o donut proporcional para a distribuição %i/%i/%i', (verdes, amarelos, vermelhos) => {
    const total = verdes + amarelos + vermelhos;
    component.dados = {
      ...payload,
      totalPacientesAtivos: total,
      distribuicaoClassificacao: {
        verdes, amarelos, vermelhos,
        percentualVerdes: verdes / total * 100,
        percentualAmarelos: amarelos / total * 100,
        percentualVermelhos: vermelhos / total * 100,
      },
    };
    expect(component.segmentosDonut.reduce((soma, segmento) => soma + segmento.tamanho, 0)).toBeCloseTo(100);
  });
  it('renderiza o estado neutro do donut sem dividir por zero', () => {
    const payloadVazio = {
      ...payload,
      totalPacientesAtivos: 0,
      distribuicaoClassificacao: { verdes: 0, amarelos: 0, vermelhos: 0, percentualVerdes: 0, percentualAmarelos: 0, percentualVermelhos: 0 },
    };
    service.buscarResumo.mockReturnValue(of(payloadVazio));
    fixture.detectChanges();
    expect(component.segmentosDonut.every(segmento => segmento.tamanho === 0)).toBe(true);
    expect(fixture.nativeElement.querySelectorAll('.donut-segment')).toHaveLength(0);
    expect(fixture.nativeElement.querySelector('.donut-center').textContent).toContain('0');
  });

  it('descarta resposta antiga quando filtro muda',()=>{
    const antiga=new Subject<typeof payload>(), recente=new Subject<typeof payload>();
    service.buscarResumo.mockReturnValueOnce(antiga).mockReturnValueOnce(recente);
    fixture.detectChanges();
    component.filtros.periodoMeses=3;
    component.alterarFiltroPrincipal();
    recente.next({...payload,totalPacientesAtivos:7});recente.complete();
    antiga.next({...payload,totalPacientesAtivos:99});antiga.complete();
    expect(component.dados?.totalPacientesAtivos).toBe(7);
    expect(component.carregando).toBe(false);
  });
  it('informa estados não confirmados sem somá-los aos ativos',()=>{
    service.buscarResumo.mockReturnValue(of({...payload,estadoNaoConfirmado:2}));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('2 vínculo(s) com estado não confirmado');
    expect(component.dados?.totalPacientesAtivos).toBe(3);
  });
});
