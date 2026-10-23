export const TIPOS_USUARIO = ['ADMINISTRADOR', 'RECEPCAO', 'PROFISSIONAL'] as const;
export type TipoUsuario = (typeof TIPOS_USUARIO)[number];

export const podeCadastrarPaciente = (tipo: string): boolean =>
  tipo === 'ADMINISTRADOR' || tipo === 'RECEPCAO';
export const podeEditarPaciente = (tipo: string): boolean =>
  tipo === 'ADMINISTRADOR' || tipo === 'RECEPCAO';