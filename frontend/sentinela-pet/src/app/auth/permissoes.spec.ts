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

  it('mantém o profissional apenas com acesso de consulta', () => {
    expect(podeCadastrarPaciente('PROFISSIONAL')).toBe(false);
    expect(podeEditarPaciente('PROFISSIONAL')).toBe(false);
  });
});