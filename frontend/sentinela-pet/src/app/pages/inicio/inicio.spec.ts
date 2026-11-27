import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { InicioService, ResumoInicioPayload } from '../../services/inicio-service';
import { Inicio } from './inicio';

describe('Inicio', () => {
  const resumo: ResumoInicioPayload = { totalPacientesAtivos: 12, totalAgendamentosHoje: 2, totalFaltasUltimosSeteDias: 1, totalPacientesAtencao: 3, totalNotificacoes: 2, pacientesPrioritarios: [] };
  const buscarResumo = vi.fn(() => of(resumo));

  beforeEach(async () => {
    buscarResumo.mockClear();
    await TestBed.configureTestingModule({
      imports: [Inicio],
      providers: [provideRouter([]), { provide: InicioService, useValue: { buscarResumo } }],
    }).compileComponents();
  });

  it('should create e exibir os indicadores da resposta', () => {
    const fixture = TestBed.createComponent(Inicio);
    fixture.detectChanges();
    expect(fixture.componentInstance).toBeTruthy();
    expect(buscarResumo).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.querySelector('h1')?.textContent).toContain('Visão geral da unidade');
    expect(fixture.nativeElement.querySelector('.indicator-value')?.textContent).toContain('12');
    expect(fixture.nativeElement.textContent).toContain('Nenhum paciente prioritário encontrado.');
  });

  it('descarta dados anteriores após falha e preserva zero e nulo', () => {
    const fixture = TestBed.createComponent(Inicio);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    buscarResumo.mockReturnValueOnce(of({ ...resumo, totalPacientesAtivos: 0, totalFaltasUltimosSeteDias: null }));
    component.buscarResumo();
    expect(component.valorIndicador('totalPacientesAtivos')).toBe(0);
    expect(component.valorIndicador('totalFaltasUltimosSeteDias')).toBe('Não confirmado');
    buscarResumo.mockReturnValueOnce(throwError(() => new Error('falha sintética')));
    component.buscarResumo();
    expect(component.resumo).toBeUndefined();
    expect(component.valorIndicador('totalPacientesAtivos')).toBe('—');
    expect(component.erro).toBe(true);
  });

  it('ignora resultado de consulta superada', () => {
    const antiga = new Subject<ResumoInicioPayload>();
    buscarResumo.mockReturnValueOnce(antiga.asObservable());
    const fixture = TestBed.createComponent(Inicio);
    fixture.detectChanges();
    fixture.componentInstance.buscarResumo();
    antiga.next({ ...resumo, totalPacientesAtivos: 99 });
    expect(fixture.componentInstance.resumo?.totalPacientesAtivos).toBe(12);
  });
});
