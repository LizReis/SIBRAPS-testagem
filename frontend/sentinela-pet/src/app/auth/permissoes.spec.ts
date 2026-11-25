import { describe, expect, it } from 'vitest';

import { podeCadastrarPaciente, podeEditarPaciente } from './permissoes';

describe('permissões de pacientes', () => {
  it.each(['ADMINISTRADOR', 'RECEPCAO'])(
    'permite que %s cadastre e edite pacientes',
    (tipoUsuario) => {
      expect(podeCadastrarPaciente(tipoUsuario)).toBe(true);
      expect(podeEditarPaciente(tipoUsuario)).toBe(true);
    },
  );

  it('permite cadastro e edição cadastral ao profissional', () => {
    expect(podeCadastrarPaciente('PROFISSIONAL')).toBe(true);
    expect(podeEditarPaciente('PROFISSIONAL')).toBe(true);
  });

  it('nao autoriza perfis desconhecidos a cadastrar ou editar', () => {
    expect(podeCadastrarPaciente('DESCONHECIDO')).toBe(false);
    expect(podeEditarPaciente('DESCONHECIDO')).toBe(false);
  });
});