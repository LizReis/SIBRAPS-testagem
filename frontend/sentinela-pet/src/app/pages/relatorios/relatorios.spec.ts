import { HttpHeaders, HttpResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { GrupoTerapeuticoService } from '../../services/grupo-terapeutico-service';
import { ProfissionalService } from '../../services/profissional/profissional-service';
import { RelatorioService } from '../../services/relatorio-service';
import { converterPeriodo, Relatorios } from './relatorios';

describe('Relatorios', () => {
  let fixture: ComponentFixture<Relatorios>;
  let component: Relatorios;
let relatorioService: {
    baixarBuscaAtivaPdf: ReturnType<typeof vi.fn>;
    baixarFrequenciaGruposPdf: ReturnType<typeof vi.fn>;
    baixarBuscaAtivaExcel: ReturnType<typeof vi.fn>;
    baixarFrequenciaGruposExcel: ReturnType<typeof vi.fn>;
  };  const respostaPdf = new HttpResponse({
    body: new Blob(['%PDF'], { type: 'application/pdf' }),
    headers: new HttpHeaders({ 'Content-Type': 'application/pdf', 'Content-Disposition': 'attachment; filename="servidor.pdf"' }),
  });

  const respostaExcel = new HttpResponse({
    body: new Blob(['xlsx'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }),
    headers: new HttpHeaders({
      'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'Content-Disposition': 'attachment; filename="servidor.xlsx"',
    }),
  });

  beforeEach(async () => {
    relatorioService = {
      baixarBuscaAtivaPdf: vi.fn().mockReturnValue(of(respostaPdf)),
      baixarFrequenciaGruposPdf: vi.fn().mockReturnValue(of(respostaPdf)),
      baixarBuscaAtivaExcel: vi.fn().mockReturnValue(of(respostaExcel)),
      baixarFrequenciaGruposExcel: vi.fn().mockReturnValue(of(respostaExcel)),
    };    await TestBed.configureTestingModule({
      imports: [Relatorios],
      providers: [
        { provide: RelatorioService, useValue: relatorioService },
        { provide: ProfissionalService, useValue: { listarElegiveisParaReferencia: () => of([]) } },
        { provide: GrupoTerapeuticoService, useValue: { listarGrupos: () => of([]) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Relatorios);
    component = fixture.componentInstance;
    fixture.detectChanges();
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:pdf');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
  });

  afterEach(() => vi.restoreAllMocks());

  it('converte período usando datas locais e omite todo o período', () => {
    expect(converterPeriodo('Últimos 30 dias', new Date(2026, 8, 16))).toEqual({ dataInicio: '2026-08-17', dataFim: '2026-09-16' });
    expect(converterPeriodo('Últimos 3 meses', new Date(2026, 8, 16))).toEqual({ dataInicio: '2026-06-16', dataFim: '2026-09-16' });
    expect(converterPeriodo('Todo o período', new Date(2026, 8, 16))).toEqual({});
  });

  it('monta os filtros de busca ativa com IDs e enums, omitindo opções irrestritas', () => {
    component.periodoBusca = 'Todo o período';
    expect(component.filtrosBuscaAtiva()).toEqual({});
    component.profissionalSelecionado = 'uuid-real';
    component.tipoAcompanhamentoSelecionado = 'Individual';
    expect(component.filtrosBuscaAtiva()).toEqual({ profissionalId: 'uuid-real', tipoAcompanhamento: 'INDIVIDUAL' });
    component.tipoAcompanhamentoSelecionado = 'Grupo terapêutico';
    expect(component.filtrosBuscaAtiva().tipoAcompanhamento).toBe('GRUPO_TERAPEUTICO');
    component.tipoAcompanhamentoSelecionado = 'Individual e grupo';
    expect(component.filtrosBuscaAtiva().tipoAcompanhamento).toBeUndefined();
  });

  it('monta filtro de grupo, omitindo Todos os grupos', () => {
    component.periodoFrequencia = 'Todo o período';
    expect(component.filtrosFrequencia()).toEqual({});
    component.grupoSelecionado = 7;
    expect(component.filtrosFrequencia()).toEqual({ grupoId: 7 });
  });

  it('exporta pela API correspondente à aba e usa Content-Disposition no download', () => {
    component.abaAtiva = 'buscaAtiva';
    component.exportarPdf();
    expect(relatorioService.baixarBuscaAtivaPdf).toHaveBeenCalledOnce();
    const link = document.querySelector('a[download="servidor.pdf"]');
    expect(link).toBeNull(); // o elemento temporário já foi removido
    expect(HTMLAnchorElement.prototype.click).toHaveBeenCalled();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:pdf');
    expect(component.exportandoPdf).toBe(false);

    component.abaAtiva = 'frequenciaGrupo';
    component.exportarPdf();
    expect(relatorioService.baixarFrequenciaGruposPdf).toHaveBeenCalledOnce();
  });

  it('mantém loading durante a requisição e o limpa no sucesso', () => {
    const pendente = new Subject<HttpResponse<Blob>>();
    relatorioService.baixarBuscaAtivaPdf.mockReturnValue(pendente);
    component.exportarPdf();
    expect(component.exportandoPdf).toBe(true);
    component.exportarPdf();
    expect(relatorioService.baixarBuscaAtivaPdf).toHaveBeenCalledOnce();
    pendente.next(respostaPdf);
    pendente.complete();
    expect(component.exportandoPdf).toBe(false);
  });

  it('limpa loading e mostra mensagem amigável em erro', () => {
    relatorioService.baixarBuscaAtivaPdf.mockReturnValue(throwError(() => new Error('interno')));
    component.exportarPdf();
    expect(component.exportandoPdf).toBe(false);
    expect(component.erroExportacao).toBe('Não foi possível gerar o relatório em PDF. Tente novamente.');
    expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled();
  });

  it('recusa resposta que não seja PDF', () => {
    relatorioService.baixarBuscaAtivaPdf.mockReturnValue(of(new HttpResponse({ body: new Blob(['{}'], { type: 'application/json' }) })));
    component.exportarPdf();
    expect(component.erroExportacao).toContain('Não foi possível');
    expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled();
  });

  it('exporta Excel pela API da aba e respeita o nome do backend', () => {
    component.abaAtiva = 'buscaAtiva';
    component.selecionarFormato('Excel');
    expect(relatorioService.baixarBuscaAtivaExcel).toHaveBeenCalledOnce();
    expect(HTMLAnchorElement.prototype.click).toHaveBeenCalledOnce();
    expect(component.exportandoPdf).toBe(false);

    component.abaAtiva = 'frequenciaGrupo';
    component.selecionarFormato('Excel');
    expect(relatorioService.baixarFrequenciaGruposExcel).toHaveBeenCalledOnce();
  });
});
