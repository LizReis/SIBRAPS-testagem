import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { Agenda } from './agenda';
import { AgendamentoDTO } from '../../services/agendamento-service';

const base: AgendamentoDTO = {
  id: 1,
  usuarioId: 'prof-1',
  nomeProfissional: 'João Ferreira',
  pacienteId: 'pac-1',
  nomePaciente: 'Lizandra Reis com nome suficientemente longo para teste',
  tipoAcompanhamento: 'INDIVIDUAL',
  dataAgendamento: '2026-08-05',
  turnoAgendamento: 'TARDE',
  horaAtendimento: '15:45:00',
  situacaoAtendimento: 'AGENDADO',
  situacaoReserva: 'AGENDADA',
  frequenciaAssistencial: 'NAO_REGISTRADA',
  version: 1,
};

function criarComponente(servicos = {}): Agenda {
  const usuarioService = { obterUsuarioLogado: () => of({ idPublico: 'u', nome: 'Admin', email: 'a@a.com', tipoUsuario: 'ADMINISTRADOR', unidadeAtuacao: 'CAPS' }) };
  const profissionalService = { listar: () => of([]) };
  const agendamentoService = { buscarAgendaPorPeriodo: () => of([]), atualizarStatus: () => of(base), ...servicos };
  const router = { navigate: () => Promise.resolve(true) };
  const agenda = new Agenda(usuarioService as any, profissionalService as any, agendamentoService as any, router as any, { markForCheck() {} } as any);
  agenda.usuarioLogado = { idPublico: 'admin-sintetico', nome: 'Admin', email: '', tipoUsuario: 'ADMINISTRADOR', unidadeAtuacao: 'CAPS_I' };
  return agenda;
}

describe('Agenda', () => {
  it.each([
    ['2026-09-01', 1], // terça-feira na segunda coluna (grade começa na segunda)
    ['2026-11-01', 6], // domingo na última coluna
    ['2026-06-01', 0], // segunda-feira na primeira coluna
  ])('alinha o primeiro dia de %s à coluna semanal correta', (iso, coluna) => {
    const component = criarComponente();
    component.dataSelecionada = iso;
    const grade = component.gradeMes;
    const indice = grade.findIndex(d => d.iso === iso);
    expect(indice % 7).toBe(coluna);
    expect(component.diasSemanaCurtos).toEqual(['S', 'T', 'Q', 'Q', 'S', 'S', 'D']);
  });

  it('mantém a mudança de mês alinhada e sem parsing UTC', () => {
    const component = criarComponente();
    component.dataSelecionada = '2026-09-01';
    const grade = component.gradeMes;
    expect(grade.find(d => d.iso === '2026-09-01')?.data.getDay()).toBe(2);
    expect(grade.some(d => d.iso === '2026-08-31' && d.foraDoMes)).toBe(true);
  });
  it('agrupa atendimento às 15:45 no bloco numérico das 15:00 sem arredondar', () => {
    const component = criarComponente();
    component.dataSelecionada = '2026-08-05';
    component.agendamentos = [base];

    const bloco = component.blocosHorariosDia.find((item) => item.hora === 15);

    expect(component.formatarHora(base.horaAtendimento)).toBe('15:45');
    expect(bloco?.label).toBe('15:00');
    expect(bloco?.agendamentos.map((a) => a.id)).toEqual([base.id]);
  });

  it('mantém atendimentos com minutos diferentes de zero empilhados e ordenados por hora e id', () => {
    const component = criarComponente();
    component.dataSelecionada = '2026-08-05';
    component.agendamentos = [
      { ...base, id: 1, horaAtendimento: '08:05:00' },
      { ...base, id: 2, horaAtendimento: '10:30:00' },
      { ...base, id: 3, horaAtendimento: '15:45:00' },
    ];

    component.agendamentos.push(
      { ...base, id: 5, horaAtendimento: '16:20:00' },
      { ...base, id: 4, horaAtendimento: '16:20:00' },
      { ...base, id: 6, horaAtendimento: '16:35:00' },
    );

    const bloco16 = component.blocosHorariosDia.find((item) => item.hora === 16);

    expect(component.agendamentosDia.map((a) => component.formatarHora(a.horaAtendimento))).toContain('15:45');
    expect(bloco16?.agendamentos.map((a) => `${component.formatarHora(a.horaAtendimento)}-${a.id}`)).toEqual(['16:20-4', '16:20-5', '16:35-6']);
  });

  it('exibe todas as situações no resumo, inclusive zeros e REMARCADO_ORIGEM como AGENDAMENTO ANTERIOR', () => {
    const component = criarComponente();
    component.agendamentos = [{ ...base, situacaoAtendimento: 'AGENDADO' }];

    expect(component.labelSituacao('REMARCADO_ORIGEM')).toBe('AGENDAMENTO ANTERIOR');
    expect(component.resumoSituacoesCompleto.map((item) => `${item.label}:${item.total}`)).toEqual([
      'AGENDADO:1',
      'PRESENTE:0',
      'FALTOU:0',
      'REMARCADO:0',
      'CANCELADO:0',
      'AGENDAMENTO ANTERIOR:0',
    ]);
  });

  it('preserva a justificativa e exige consulta e nova decisão após versão desatualizada', () => {
    const correcao = { ...base, frequenciaAssistencial: 'PRESENTE' } as const;
    const atual = { ...correcao, version: 2 };
    const corrigirFrequencia = vi.fn()
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 422, error: { code: 'STALE_VERSION' } })))
      .mockReturnValueOnce(of(atual));
    const buscarAgendaPorPeriodo = vi.fn().mockReturnValue(of([atual]));
    const component = criarComponente({ corrigirFrequencia, buscarAgendaPorPeriodo });
    component.corrigirFrequencia(correcao);
    component.justificativa = 'Justificativa sintética conferida.';

    component.confirmarCorrecao();
    expect(component.revisaoDesatualizada).toBe(true);
    expect(component.justificativa).toBe('Justificativa sintética conferida.');
    expect(component.erroGeral).toContain('desatualizados');
    expect(buscarAgendaPorPeriodo).not.toHaveBeenCalled();
    component.confirmarCorrecao();
    expect(corrigirFrequencia).toHaveBeenCalledOnce();

    component.tentarNovamente();
    component.corrigirFrequencia(atual);
    expect(component.justificativa).toBe('Justificativa sintética conferida.');
    expect(corrigirFrequencia).toHaveBeenCalledOnce();
    component.confirmarCorrecao();
    expect(corrigirFrequencia).toHaveBeenLastCalledWith(base.id, 'FALTOU', 2, 'Justificativa sintética conferida.');
  });

  it('exibe o conflito de negócio sem atribuir alteração a outro profissional', () => {
    const atualizarStatus = vi.fn().mockReturnValue(throwError(() =>
      new HttpErrorResponse({ status: 409, error: { code: 'BUSINESS_CONFLICT', message: 'Já há frequência registrada.' } })));
    const component = criarComponente({ atualizarStatus });
    component.registrarPresenca(base);
    expect(component.erroGeral).toBe('Já há frequência registrada.');
    expect(atualizarStatus).toHaveBeenCalledOnce();
  });

  it('controla exibição das ações principais no card', () => {
    const component = criarComponente();
    component.podeCriarAgendamento = true;
    component.dataSelecionada = '2026-08-05';

    expect(component.podeRegistrarFrequencia({ ...base, dataAgendamento: '2020-01-01' })).toBe(true);
    expect(component.podeRemarcar({ ...base, tipoAcompanhamento: 'INDIVIDUAL' })).toBe(true);
    expect(component.podeRemarcar({ ...base, situacaoAtendimento: 'CANCELADO', situacaoReserva: 'CANCELADA' })).toBe(false);
    expect(component.podeRemarcar({ ...base, tipoAcompanhamento: 'GRUPO_TERAPEUTICO' })).toBe(false);
  });
  it('preserva presença de reserva cancelada e distingue frequência inconclusiva', () => {
    const component = criarComponente();
    const cancelada: AgendamentoDTO = { ...base, situacaoReserva: 'CANCELADA', frequenciaAssistencial: 'PRESENTE' };
    component.agendamentos = [cancelada, { ...base, frequenciaAssistencial: null }];
    expect(component.resumoCanonico).toEqual(expect.arrayContaining([
      { label: 'Reserva: Cancelada', total: 1 }, { label: 'Frequência: Presente', total: 1 },
      { label: 'Frequência: Não confirmada', total: 1 },
    ]));
    expect(component.podeRegistrarFrequencia(cancelada)).toBe(false);
    expect(component.podeRegularizar(cancelada)).toBe(false);
    expect(component.podeCorrigirFrequencia(cancelada)).toBe(true);
    component.usuarioLogado = { ...component.usuarioLogado!, tipoUsuario: 'RECEPCAO' };
    expect(component.podeCorrigirFrequencia(cancelada)).toBe(false);
  });
});
