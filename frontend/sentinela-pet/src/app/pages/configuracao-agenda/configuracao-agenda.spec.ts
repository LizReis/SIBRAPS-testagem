import { HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';

import { ConfiguracaoAgenda } from './configuracao-agenda';

describe('ConfiguracaoAgenda - formulários inline', () => {
  let component: ConfiguracaoAgenda;
  let disponibilidade: any;
  let bloqueio: any;
  let excecao: any;
  let usuarioLogado: any;
  let profissionais: any;

  beforeEach(() => {
    disponibilidade = {
      listar: vi.fn(() => of([])),
      salvar: vi.fn(() => of({})),
      remover: vi.fn(() => of(void 0)),
    };
    bloqueio = {
      listar: vi.fn(() => of([])),
      salvar: vi.fn(() => of({})),
      remover: vi.fn(() => of(void 0)),
    };
    excecao = {
      listar: vi.fn(() => of([])),
      salvar: vi.fn(() => of({})),
      remover: vi.fn(() => of(void 0)),
    };

    usuarioLogado = { obterUsuarioLogado: vi.fn(() => of({ tipoUsuario: 'ADMINISTRADOR', nome: 'Admin' })) };
    profissionais = { listarParaSelecao: vi.fn(() => of([
      { idPublico: 'admin', nome: 'Admin', tipoUsuario: 'ADMINISTRADOR' },
      { idPublico: 'prof', nome: 'Profissional', tipoUsuario: 'PROFISSIONAL' },
    ])) };
    component = new ConfiguracaoAgenda(
      usuarioLogado,
      profissionais,
      disponibilidade,
      bloqueio,
      excecao,
    );

    component.isAdmin = true;
    component.profissionalSelecionadoId =
      '61e45a3c-f6df-4f54-8c56-6b46f874e092';
  });

  it('recebe administrador e profissional pelo endpoint de seleção e configura a própria agenda', () => {
    component.ngOnInit();
    expect(profissionais.listarParaSelecao).toHaveBeenCalledOnce();
    expect(component.profissionais.map(p => p.idPublico)).toEqual(['admin', 'prof']);
    component.profissionalSelecionadoId = 'admin';
    component.onProfissionalSelecionado();
    expect(disponibilidade.listar).toHaveBeenCalledWith('admin');
    component.disponibilidadeForm = { diaSemana: 'MONDAY', turno: 'MANHA', capacidade: 3 };
    component.salvarHorario();
    expect(disponibilidade.salvar).toHaveBeenCalledWith(expect.objectContaining({ usuarioId: 'admin' }));
  });

  it('envia horário com idPublico do profissional e capacidade numérica', () => {
    component.disponibilidadeForm = {
      diaSemana: 'MONDAY',
      turno: 'MANHA',
      capacidade: '5' as any,
    };

    component.salvarHorario();

    expect(disponibilidade.salvar).toHaveBeenCalledWith({
      diaSemana: 'MONDAY',
      turno: 'MANHA',
      capacidade: 5,
      usuarioId: '61e45a3c-f6df-4f54-8c56-6b46f874e092',
    });
    expect(component.modal).toBeNull();
    expect(disponibilidade.listar).toHaveBeenCalled();
  });

  it('omite usuarioId ao salvar como profissional', () => {
    component.isAdmin = false;
    component.isProfissional = true;
    component.profissionalSelecionadoId = null;
    component.disponibilidadeForm = {
      diaSemana: 'TUESDAY',
      turno: 'TARDE',
      capacidade: 2,
    };

    component.salvarHorario();

    expect(disponibilidade.salvar.mock.calls[0][0].usuarioId).toBeUndefined();
  });

  it('não envia horário de administrador sem profissional selecionado', () => {
    component.profissionalSelecionadoId = null;
    component.disponibilidadeForm = {
      diaSemana: 'MONDAY',
      turno: 'MANHA',
      capacidade: 5,
    };

    component.salvarHorario();

    expect(disponibilidade.salvar).not.toHaveBeenCalled();
    expect(component.erroHorario).toContain('Selecione um profissional');
  });

  it('envia datas ISO e motivo obrigatório no bloqueio', () => {
    component.bloqueioForm = {
      dataInicio: '2026-08-10',
      dataFim: '2026-08-15',
      motivoBloqueio: ' Férias ',
    };

    component.salvarBloqueio();

    expect(bloqueio.salvar).toHaveBeenCalledWith(
      expect.objectContaining({
        dataInicio: '2026-08-10',
        dataFim: '2026-08-15',
        motivoBloqueio: 'Férias',
      }),
    );
  });

  it('não envia bloqueio com intervalo inválido', () => {
    component.bloqueioForm = {
      dataInicio: '2026-08-15',
      dataFim: '2026-08-10',
      motivoBloqueio: 'Férias',
    };

    component.salvarBloqueio();

    expect(bloqueio.salvar).not.toHaveBeenCalled();
  });

  it('aceita capacidade zero na exceção e envia number', () => {
    component.excecaoForm = {
      data: '2026-08-20',
      turno: 'TARDE',
      capacidade: '0' as any,
    };

    component.salvarExcecao();

    expect(excecao.salvar).toHaveBeenCalledWith(
      expect.objectContaining({ capacidade: 0 }),
    );
  });

  it('rejeita capacidade negativa na exceção', () => {
    component.excecaoForm = {
      data: '2026-08-20',
      turno: 'TARDE',
      capacidade: -1,
    };

    component.salvarExcecao();

    expect(excecao.salvar).not.toHaveBeenCalled();
  });

  it('mantém o formulário inline e os campos quando backend retorna conflito', () => {
    disponibilidade.salvar.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { message: 'Já existe disponibilidade cadastrada.' },
          }),
      ),
    );
    component.disponibilidadeForm = {
      diaSemana: 'MONDAY',
      turno: 'MANHA',
      capacidade: 5,
    };

    component.salvarHorario();

    expect(component.modal).toBeNull();
    expect(component.disponibilidadeForm.capacidade).toBe(5);
    expect(component.erroHorario).toBe(
      'Já existe disponibilidade cadastrada.',
    );
  });

  it('impede duplo envio enquanto a primeira requisição está pendente', () => {
    const resposta = new Subject();
    disponibilidade.salvar.mockReturnValue(resposta);
    component.disponibilidadeForm = {
      diaSemana: 'MONDAY',
      turno: 'MANHA',
      capacidade: 5,
    };

    component.salvarHorario();
    component.salvarHorario();

    expect(disponibilidade.salvar).toHaveBeenCalledOnce();
    expect(component.salvandoHorario).toBe(true);
  });

  it('preserva aba ativa e atualiza coleções após sucesso', () => {
    component.abaAtiva = 'excecoes';
    excecao.listar.mockReturnValue(
      of([
        {
          id: 1,
          data: '2026-08-20',
          turno: 'TARDE',
          capacidade: 0,
        },
      ]),
    );
    component.excecaoForm = {
      data: '2026-08-20',
      turno: 'TARDE',
      capacidade: 0,
    };

    component.salvarExcecao();

    expect(component.abaAtiva).toBe('excecoes');
    expect(component.excecoes).toHaveLength(1);
    expect(component.excecoes[0].capacidade).toBe(0);
  });

  it('preenche e cancela a edição inline de horário', () => {
    component.abrirHorario({
      id: 1,
      diaSemana: 'MONDAY',
      turno: 'MANHA',
      capacidade: 4,
    });
    expect(component.disponibilidadeForm.id).toBe(1);

    component.cancelarEdicaoHorario();

    expect(component.disponibilidadeForm).toEqual({
      diaSemana: '',
      turno: '',
      capacidade: 1,
    });
  });

  it('preenche e cancela a edição inline de bloqueio', () => {
    component.abrirBloqueio({
      id: 2,
      dataInicio: '2026-08-10',
      dataFim: '2026-08-11',
      motivoBloqueio: 'Curso',
    });
    expect(component.bloqueioForm.id).toBe(2);

    component.cancelarEdicaoBloqueio();

    expect(component.bloqueioForm).toEqual({
      dataInicio: '',
      dataFim: '',
      motivoBloqueio: '',
    });
  });

  it('preenche e cancela a edição inline de exceção', () => {
    component.abrirExcecao({
      id: 3,
      data: '2026-08-20',
      turno: 'TARDE',
      capacidade: 0,
    });
    expect(component.excecaoForm.id).toBe(3);

    component.cancelarEdicaoExcecao();

    expect(component.excecaoForm).toEqual({
      data: '',
      turno: '',
      capacidade: 1,
    });
  });

  it('mantém a confirmação segura e exclui pelo serviço atual', () => {
    component.confirmarExclusao('horarios', 9);
    expect(component.modal).toBe('confirmacao');

    component.excluir();

    expect(disponibilidade.remover).toHaveBeenCalledWith(9);
    expect(component.modal).toBeNull();
  });
});
