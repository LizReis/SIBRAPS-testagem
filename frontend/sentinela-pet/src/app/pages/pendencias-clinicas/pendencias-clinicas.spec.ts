import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';
import { PendenciasClinicas } from './pendencias-clinicas';
import { RelatorioService } from '../../services/relatorio-service';
import { PaginaPendenciasLocal, PendenciaLocal } from '../../services/contrato-clinico-local';

describe('PendenciasClinicas', () => {
  const item: PendenciaLocal = {ticket:'ticket-sintetico', paciente:'paciente-sintetico', vinculo:'vinculo-sintetico',
    nome:'Paciente sintético', tipoRegistro:'HISTORICO', solicitadoEm:'2026-11-01T12:00:00Z', estado:'PENDENTE',
    decisao:null, decididaEm:null, versaoVinculo:5, registro:1, justificativaSolicitacao:'Revisão sintética solicitada',
    justificativaDecisao:null, revisaoOrigem:true, aplicacaoOrigem:'SEM_APLICACAO'};
  const pagina: PaginaPendenciasLocal = {pendentes:1, aprovadas:0, rejeitadas:0, itens:[item], total:1, pagina:0, tamanho:10};
  let fixture: ComponentFixture<PendenciasClinicas>;
  let c: PendenciasClinicas;
  let api: {pendencias: ReturnType<typeof vi.fn>; decidir: ReturnType<typeof vi.fn>};
  beforeEach(async () => {
    api = {pendencias:vi.fn().mockReturnValue(of(pagina)), decidir:vi.fn().mockReturnValue(of('decisao-sintetica'))};
    await TestBed.configureTestingModule({imports:[PendenciasClinicas], providers:[provideRouter([]), {provide:RelatorioService,useValue:api}]}).compileComponents();
    fixture=TestBed.createComponent(PendenciasClinicas);
    c=fixture.componentInstance;
    fixture.detectChanges();
  });
  it('consulta a fila e distingue decisão de aplicação', () => {
    expect(api.pendencias).toHaveBeenCalledWith(0, undefined);
    expect(fixture.nativeElement.textContent).toContain('Sem aplicação');
    expect(c.rotuloAplicacao({...item, estado:'APROVADA', aplicacaoOrigem:'REVERTIDA'})).toBe('Revertida');
    expect(c.rotuloAplicacao({...item, aplicacaoOrigem:undefined})).toBe('Aplicação não informada');
  });
  it('registra uma decisão somente com justificativa e recarrega sem aplicar correção', () => {
    c.detalhar(item);
    c.justificativa='curta';
    c.decidir('APROVAR');
    expect(api.decidir).not.toHaveBeenCalled();
    c.justificativa='Justificativa sintética suficiente';
    c.decidir('APROVAR');
    expect(api.decidir).toHaveBeenCalledExactlyOnceWith(item,'APROVAR','Justificativa sintética suficiente');
    expect(api.pendencias).toHaveBeenCalledTimes(2);
    expect(c.aviso).toContain('Nenhuma correção clínica');
  });
  it('bloqueia reenvio após conflito e mantém justificativa até consulta explícita', () => {
    api.decidir.mockReturnValue(throwError(() => new HttpErrorResponse({status:422,error:{code:'STALE_VERSION'}})));
    c.detalhar(item);
    c.justificativa='Justificativa sintética suficiente';
    c.decidir('REJEITAR');
    expect(c.exigeConsulta).toBe(true);
    expect(c.justificativa).toBe('Justificativa sintética suficiente');
    c.decidir('REJEITAR');
    expect(api.decidir).toHaveBeenCalledTimes(1);
    c.carregar();
    expect(c.selecionada).toBeUndefined();
  });
  it('não decide registro que não pertence à consulta atual',()=>{
    c.selecionada={...item,ticket:'outro'};c.justificativa='Justificativa sintética suficiente';
    c.decidir('APROVAR');expect(api.decidir).not.toHaveBeenCalled();
  });
  it('impede trocar seleção ou repetir decisão enquanto grava', () => {
    const resposta=new Subject<string>();
    api.decidir.mockReturnValue(resposta);
    c.detalhar(item);
    c.justificativa='Justificativa sintética suficiente';
    c.decidir('APROVAR');
    c.carregar(1);
    c.decidir('APROVAR');
    expect(api.decidir).toHaveBeenCalledTimes(1);
    expect(api.pendencias).toHaveBeenCalledTimes(1);
    resposta.complete();
  });
});
