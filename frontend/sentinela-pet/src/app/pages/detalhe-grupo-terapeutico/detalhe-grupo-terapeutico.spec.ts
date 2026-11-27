import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { throwError } from 'rxjs';
import { GrupoTerapeuticoService, SessaoGrupoPayload } from '../../services/grupo-terapeutico-service';
import { DetalheGrupoTerapeutico } from './detalhe-grupo-terapeutico';

describe('DetalheGrupoTerapeutico', () => {
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
    componente.sessao = {
      id: 2, version: 1, participantes: [{ pacienteId: 'sintetico', statusPresenca: 'FALTOU' }],
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
});
