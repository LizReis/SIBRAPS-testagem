import { of } from 'rxjs';
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
  version: 1,
};

function criarComponente(): Agenda {
  const usuarioService = { obterUsuarioLogado: () => of({ idPublico: 'u', nome: 'Admin', email: 'a@a.com', tipoUsuario: 'ADMINISTRADOR', unidadeAtuacao: 'CAPS' }) };
  const profissionalService = { listar: () => of([]) };
  const agendamentoService = { buscarAgendaPorPeriodo: () => of([]), atualizarStatus: () => of(base) };
  const router = { navigate: () => Promise.resolve(true) };
  return new Agenda(usuarioService as any, profissionalService as any, agendamentoService as any, router as any);
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

  it('controla exibição das ações principais no card', () => {
    const component = criarComponente();
    component.podeCriarAgendamento = true;
    component.dataSelecionada = '2026-08-05';

    expect(component.podeRegistrarFrequencia({ ...base, dataAgendamento: '2020-01-01' })).toBe(true);
    expect(component.podeRemarcar({ ...base, tipoAcompanhamento: 'INDIVIDUAL' })).toBe(true);
    expect(component.podeRemarcar({ ...base, situacaoAtendimento: 'CANCELADO' })).toBe(false);
    expect(component.podeRemarcar({ ...base, tipoAcompanhamento: 'GRUPO_TERAPEUTICO' })).toBe(false);
  });
});
