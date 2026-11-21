import { describe, expect, it } from 'vitest';

import { podeCadastrarPaciente, podeEditarPaciente } from './permissoes';

describe('permissões de pacientes', () => {
  it.each(['ADMINISTRADOR', 'RECEPCAO', 'PROFISSIONAL'])(
    'permite que %s cadastre e edite pacientes',
    (tipoUsuario) => {
      expect(podeCadastrarPaciente(tipoUsuario)).toBe(true);
      expect(podeEditarPaciente(tipoUsuario)).toBe(true);
    },
  );

  it('permite cadastro ao profissional sem conceder edicao', () => {
    expect(podeCadastrarPaciente('PROFISSIONAL')).toBe(true);
    expect(podeEditarPaciente('PROFISSIONAL')).toBe(false);
  });

  it('nao autoriza perfis desconhecidos a cadastrar ou editar', () => {
    expect(podeCadastrarPaciente('DESCONHECIDO')).toBe(false);
    expect(podeEditarPaciente('DESCONHECIDO')).toBe(false);
  });
});