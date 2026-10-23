import { Routes } from '@angular/router';
import { Login } from './pages/login/login';
import { AuthLayout } from './layouts/auth-layout/auth-layout';
import { MainLayout } from './layouts/main-layout/main-layout';
import { Inicio } from './pages/inicio/inicio';
import { Painel } from './pages/painel/painel';
import { Agenda } from './pages/agenda/agenda';
import { Pacientes } from './pages/pacientes/pacientes';
import { CadastroPaciente } from './pages/cadastro-paciente/cadastro-paciente';
import { loginGuard } from './auth/login-guard';
import { authGuard } from './auth/auth-guard';
import { Profissionais } from './pages/profissionais/profissionais';
import { CadastroProfissional } from './pages/cadastro-profissional/cadastro-profissional';
import { DetalhePaciente } from './pages/detalhe-paciente/detalhe-paciente';
import { DetalheProfissional } from './pages/detalhe-profissional/detalhe-profissional';
import { ConfiguracaoAgenda } from './pages/configuracao-agenda/configuracao-agenda';
import { NovoAgendamento } from './pages/novo-agendamento/novo-agendamento';
import { HistoricoPaciente } from './pages/historico-paciente/historico-paciente';
import { AgendamentosPaciente } from './pages/agendamentos-paciente/agendamentos-paciente';
import { Relatorios } from './pages/relatorios/relatorios';
import { roleGuard } from './auth/role-guard';
import { GruposTerapeuticos } from './pages/grupos-terapeuticos/grupos-terapeuticos';
import { AgendarGrupoTerapeutico } from './pages/agendar-grupo-terapeutico/agendar-grupo-terapeutico';
import { DetalheGrupoTerapeutico } from './pages/detalhe-grupo-terapeutico/detalhe-grupo-terapeutico';

const TODOS = ['ADMINISTRADOR', 'RECEPCAO', 'PROFISSIONAL'];
const ADMIN_RECEPCAO = ['ADMINISTRADOR', 'RECEPCAO'];
const ADMIN = ['ADMINISTRADOR'];

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },

  {
    path: '',
    component: AuthLayout,
    children: [{ path: 'login', component: Login, canActivate: [loginGuard] }],
  },

  {
    path: '',
    component: MainLayout,
    canActivate: [authGuard],
    children: [
      { path: 'inicio', component: Inicio },
      { path: 'painel', component: Painel },
      { path: 'agenda', component: Agenda },
      { path: 'agenda/configuracoes', component: ConfiguracaoAgenda, canActivate: [roleGuard], data: { roles: TODOS } },
      { path: 'agenda/novo', component: NovoAgendamento },
      { path: 'pacientes', component: Pacientes, canActivate: [roleGuard], data: { roles: TODOS } },
      { path: 'pacientes/detalhes/:id', component: DetalhePaciente, canActivate: [roleGuard], data: { roles: TODOS } },
      { path: 'pacientes/detalhes/:id/historico', component: HistoricoPaciente, canActivate: [roleGuard], data: { roles: TODOS } },
      { path: 'pacientes/detalhes/:id/agendamentos', component: AgendamentosPaciente, canActivate: [roleGuard], data: { roles: TODOS } },
      { path: 'pacientes/novo', component: CadastroPaciente, canActivate: [roleGuard], data: { roles: ADMIN_RECEPCAO } },
      { path: 'pacientes/editar/:id', component: CadastroPaciente, canActivate: [roleGuard], data: { roles: ADMIN_RECEPCAO } },
      { path: 'profissionais', component: Profissionais, canActivate: [roleGuard], data: { roles: ADMIN_RECEPCAO } },
      { path: 'profissionais/novo', component: CadastroProfissional, canActivate: [roleGuard], data: { roles: ADMIN } },
      { path: 'profissionais/detalhes/:id', component: DetalheProfissional, canActivate: [roleGuard], data: { roles: ADMIN_RECEPCAO } },
      { path: 'profissionais/editar/:id', component: CadastroProfissional, canActivate: [roleGuard], data: { roles: ADMIN } },
      { path: 'grupos-terapeuticos', component: GruposTerapeuticos, canActivate: [roleGuard], data: { roles: TODOS } },
      { path: 'grupos-terapeuticos/detalhes/:grupoId/sessao/:sessaoId', component: DetalheGrupoTerapeutico, canActivate: [roleGuard], data: { roles: TODOS } },
      { path: 'grupos-terapeuticos/novo', component: AgendarGrupoTerapeutico, canActivate: [roleGuard], data: { roles: TODOS } },
      { path: 'grupos-terapeuticos/editar/:grupoId', component: AgendarGrupoTerapeutico, canActivate: [roleGuard], data: { roles: TODOS } },
      { path: 'relatorios', component: Relatorios },
    ],
  },
];
