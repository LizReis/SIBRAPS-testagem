import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { PacienteService } from '../../services/paciente/paciente-service';
import { HistoricoPaciente } from './historico-paciente';

describe('HistoricoPaciente', () => {
  let fixture: ComponentFixture<HistoricoPaciente>;
  const navigate = vi.fn();
  const buscarHistorico = vi.fn();

  beforeEach(async () => {
    buscarHistorico.mockClear();
    navigate.mockClear();
    buscarHistorico.mockReturnValue(of({
      idPublico: 'paciente-publico', nomePaciente: 'Paciente', statusPaciente: 'ATIVO',
      classificacaoAtual: 'VERDE', tipoAcompanhamento: 'INDIVIDUAL', quantidadeFaltasAtual: 0,
      totalConsultasAgendadas: 0, totalPresencas: 0, totalFaltas: 0, totalRemarcacoes: 0,
      totalGruposTerapeuticos: 0, totalRegistrosBuscaAtiva: 0, eventos: [],
    }));
    await TestBed.configureTestingModule({
      imports: [HistoricoPaciente],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'paciente-publico' } } } },
        { provide: Router, useValue: { navigate } },
        { provide: PacienteService, useValue: { buscarHistorico } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(HistoricoPaciente);
  });

  it('carrega o histórico usando o idPublico da rota', () => {
    fixture.detectChanges();
    expect(buscarHistorico).toHaveBeenCalledWith('paciente-publico');
    expect(fixture.nativeElement.textContent).toContain('Nenhum evento foi registrado');
  });

  it('exibe erro e permite tentar novamente', () => {
    buscarHistorico.mockReturnValueOnce(throwError(() => new Error('falha')));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não foi possível carregar');
    fixture.nativeElement.querySelector('.btn-primario').click();
    expect(buscarHistorico).toHaveBeenCalledTimes(2);
  });

  it('distingue presença inconclusiva e totais nulos de ausência comprovada', () => {
    const base={idPublico:'paciente-publico',nomePaciente:'Paciente',statusPaciente:'ATIVO',classificacaoAtual:null,
      tipoAcompanhamento:'INDIVIDUAL',quantidadeFaltasAtual:null,totalConsultasAgendadas:1,totalPresencas:null,totalFaltas:null,
      totalRemarcacoes:0,totalGruposTerapeuticos:0,totalRegistrosBuscaAtiva:0,eventos:[],historicoLocalRequerConferencia:true};
    buscarHistorico.mockReturnValue(of({...base,ultimaPresencaConclusiva:false}));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não confirmada');
    expect(fixture.nativeElement.textContent).toContain('Não confirmado');
    expect(fixture.nativeElement.textContent).not.toContain('Nenhuma presença comprovada');
    expect(fixture.componentInstance.ultimaPresenca({...base,ultimaPresencaConclusiva:true})).toBe('Nenhuma presença comprovada');
  });
  it('limpa histórico anterior quando atualização é recusada',()=>{
    fixture.detectChanges();
    buscarHistorico.mockReturnValue(throwError(()=>new Error('falha')));
    fixture.componentInstance.carregarHistorico();
    expect(fixture.componentInstance.historico).toBeNull();
  });
});