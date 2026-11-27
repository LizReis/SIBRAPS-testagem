import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { RevisoesPaciente } from './revisoes-paciente';
import { RevisaoClinicaService } from '../../services/revisao-clinica-service';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { ProntuarioLocal } from '../../services/contrato-clinico-local';

describe('RevisoesPaciente', () => {
  const prontuario = { paciente:{idPublico:'p',vinculo:'v',versao:3,nome:'Paciente sintético',unidade:'USF sintética'},
    atendimentos:[], sessoes:[], historico:[{id:9,tipo:'FALTA',ocorridoEm:'2026-11-01T08:00:00'}] } as unknown as ProntuarioLocal;
  let fixture: ComponentFixture<RevisoesPaciente>;
  let perfil: string;
  let api: {prontuario: ReturnType<typeof vi.fn>; candidatosOrigem: ReturnType<typeof vi.fn>; solicitar: ReturnType<typeof vi.fn>};
  beforeEach(async () => {
    perfil='PROFISSIONAL';
    api={prontuario:vi.fn().mockReturnValue(of(prontuario)), candidatosOrigem:vi.fn().mockReturnValue(of({versaoVinculo:3,candidatos:[]})),
      solicitar:vi.fn().mockReturnValue(of('ticket-sintetico'))};
    await TestBed.configureTestingModule({imports:[RevisoesPaciente],providers:[provideRouter([]),
      {provide:ActivatedRoute,useValue:{paramMap:of(convertToParamMap({id:'p',vinculo:'v'}))}},
      {provide:UsuarioLogadoService,useValue:{obterUsuarioLogado:()=>of({tipoUsuario:perfil})}},
      {provide:RevisaoClinicaService,useValue:api}]}).compileComponents();
  });
  function iniciar() { fixture=TestBed.createComponent(RevisoesPaciente);fixture.detectChanges();return fixture.componentInstance; }
  it('profissional solicita revisão sem comando de correção e sem reenvio', () => {
    const c=iniciar(); c.consultarOrigem();expect(api.candidatosOrigem).not.toHaveBeenCalled();
    c.selecionar(c.registros[0]); c.justificativa='curta'; c.solicitar();expect(api.solicitar).not.toHaveBeenCalled();
    c.justificativa='Justificativa sintética suficiente'; c.solicitar(); c.solicitar();
    expect(api.solicitar).toHaveBeenCalledExactlyOnceWith('p','v',3,'HISTORICO',9,'Justificativa sintética suficiente');
    expect(c.aviso).toContain('não foram alterados');
  });
  it('recusa vínculo divergente e não disponibiliza registros', () => {
    api.prontuario.mockReturnValue(of({...prontuario,paciente:{...prontuario.paciente,vinculo:'externo'}}));
    const c=iniciar();expect(c.registros).toEqual([]);expect(c.prontuario).toBeUndefined();expect(c.podeSolicitar).toBe(false);
  });
  it('recepção não consulta registros pela jornada clínica', () => {
    perfil='RECEPCAO'; const c=iniciar(); expect(c.autorizado).toBe(false);expect(api.prontuario).not.toHaveBeenCalled();
  });
  it('admin não seleciona candidatos com versão divergente', () => {
    perfil='ADMINISTRADOR';api.candidatosOrigem.mockReturnValue(of({versaoVinculo:4,candidatos:[{historico:9}]}));
    const c=iniciar(); c.consultarOrigem();expect(c.exigeConsulta).toBe(true);expect(c.candidatos).toEqual([]);
  });
  it('mantém justificativa após falha e exige consulta antes de outra tentativa', () => {
    api.solicitar.mockReturnValue(throwError(()=>new HttpErrorResponse({status:422,error:{code:'STALE_VERSION'}})));
    const c=iniciar();c.selecionar(c.registros[0]);c.justificativa='Justificativa sintética suficiente';
    c.solicitar();c.solicitar();expect(api.solicitar).toHaveBeenCalledTimes(1);expect(c.justificativa).toContain('suficiente');
    expect(c.exigeConsulta).toBe(true);
  });
});
