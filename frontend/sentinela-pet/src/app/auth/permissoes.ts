import { contratoExperimental } from '../services/configuracao-contrato';

export const TIPOS_USUARIO = ['ADMINISTRADOR', 'RECEPCAO', 'PROFISSIONAL'] as const;
export type TipoUsuario = (typeof TIPOS_USUARIO)[number];

export const podeCadastrarPaciente = (tipo: string): boolean =>
  tipo === 'ADMINISTRADOR' || tipo === 'RECEPCAO' || tipo === 'PROFISSIONAL';
export const podeEditarPaciente = (tipo: string): boolean =>
  tipo === 'ADMINISTRADOR' || tipo === 'RECEPCAO' || (contratoExperimental && tipo === 'PROFISSIONAL');

export const podeCriarAgendamento = (tipo: string): boolean =>
  tipo === 'ADMINISTRADOR' || tipo === 'RECEPCAO';
