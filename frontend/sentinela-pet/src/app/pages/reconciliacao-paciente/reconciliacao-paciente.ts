import { CommonModule } from '@angular/common';
import { Component, ChangeDetectorRef, DestroyRef, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, forkJoin, Observable, Subscription } from 'rxjs';
import { AuditoriaFrequencia, AuditoriaOrigem, CandidatoFrequencia, CandidatosFrequencia, FatoFrequencia, RegistroResolucao, RevisaoClinicaService } from '../../services/revisao-clinica-service';
import { interpretarErroHttp } from '../../services/erro-http';

@Component({
  selector:'app-reconciliacao-paciente', standalone:true, imports:[CommonModule,FormsModule,RouterLink],
  templateUrl:'./reconciliacao-paciente.html', styleUrl:'../relatorios/relatorios.css',
})
export class ReconciliacaoPaciente implements OnInit {
  private readonly api=inject(RevisaoClinicaService);
  private readonly route=inject(ActivatedRoute);
  private readonly cdr=inject(ChangeDetectorRef);
  private readonly destroy=inject(DestroyRef);
  private consulta?: Subscription;
  private geracao=0;
  paciente=''; vinculo=''; ticket='';
  origem=false;
  auditoriaOrigem?: AuditoriaOrigem;
  candidatos?: CandidatosFrequencia;
  auditoria?: AuditoriaFrequencia;
  grupo?: CandidatoFrequencia;
  valido?: FatoFrequencia;
  desconsiderado?: FatoFrequencia;
  justificativa='';
  confirmou=false;
  carregando=false; salvando=false; exigeConsulta=true;
  erro=''; aviso='';
  paginaC=0; paginaA=0;

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(p=>{
      this.paciente=p.get('id')??''; this.vinculo=p.get('vinculo')??''; this.ticket=p.get('ticket')??'';
      this.origem=this.route.snapshot.data['origem']===true;
      this.salvando=false;this.aviso='';
      this.carregar(0,0);
    });
  }
  carregar(pc=this.paginaC,pa=this.paginaA): void {
    if(this.salvando||pc<0||pa<0)return;
    const g=++this.geracao;
    this.consulta?.unsubscribe();
    this.paginaC=pc;this.paginaA=pa;
    this.auditoriaOrigem=undefined;this.candidatos=undefined;this.auditoria=undefined;
    this.grupo=undefined;this.valido=undefined;this.desconsiderado=undefined;
    this.justificativa='';this.confirmou=false;this.erro='';this.exigeConsulta=true;
    if(!this.paciente||!this.vinculo||(this.origem&&!this.ticket)){this.erro='Registro não encontrado.';this.carregando=false;return;}
    this.carregando=true;
    const fim=()=>{if(g===this.geracao){this.carregando=false;this.cdr.markForCheck();}};
    const falha=(e:unknown)=>{this.erro=interpretarErroHttp(e,{padrao:'Não foi possível consultar os registros de revisão.'}).mensagem;};
    if(this.origem){
      this.consulta=this.api.auditoriaOrigem(this.paciente,this.vinculo,this.ticket)
        .pipe(takeUntilDestroyed(this.destroy),finalize(fim)).subscribe({
          next:a=>{this.auditoriaOrigem=a;this.exigeConsulta=!a.integridadeEventos||!Number.isSafeInteger(a.versaoVinculo)||a.versaoVinculo<0;},error:falha});
    }else{
      this.consulta=forkJoin({c:this.api.candidatosFrequencia(this.paciente,this.vinculo,pc),a:this.api.auditoriaFrequencia(this.paciente,this.vinculo,pa)})
        .pipe(takeUntilDestroyed(this.destroy),finalize(fim)).subscribe({
          next:({c,a})=>{
            this.candidatos=c;this.auditoria=a;
            if(c.versaoVinculo!==a.versaoVinculo||!Number.isSafeInteger(c.versaoVinculo)||c.versaoVinculo<0){
              this.erro='O vínculo foi alterado durante a consulta. Atualize os dados.';return;
            }
            this.exigeConsulta=false;
          },error:falha});
    }
  }
  selecionar(g:CandidatoFrequencia): void {
    if(this.bloqueado||!this.candidatos?.candidatos.includes(g))return;
    this.grupo=g;this.valido=undefined;this.desconsiderado=undefined;this.confirmou=false;this.justificativa='';
  }
  get bloqueado(): boolean {return this.carregando||this.salvando||this.exigeConsulta;}
  get motivoValido(): boolean {const n=this.justificativa.trim().length;return !this.bloqueado&&n>=10&&n<=2000;}
  get podeResolver(): boolean {
    return this.motivoValido&&this.confirmou&&!!this.grupo&&!!this.valido&&!!this.desconsiderado&&
      this.grupo.fatos.includes(this.valido)&&this.grupo.fatos.includes(this.desconsiderado)&&
      (this.valido.referencia.fonte!==this.desconsiderado.referencia.fonte||this.valido.referencia.id!==this.desconsiderado.referencia.id)&&this.desconsiderado.podeDesconsiderar;
  }
  resolver(): void {
    if(!this.podeResolver||!this.candidatos||!this.valido||!this.desconsiderado)return;
    this.gravar(this.api.resolverFrequencia(this.paciente,this.vinculo,this.candidatos.versaoVinculo,
      this.valido.referencia,this.desconsiderado.referencia,this.justificativa.trim(),this.ticket||undefined));
  }
  podeDesfazer(r:RegistroResolucao): boolean {
    return this.motivoValido&&!r.reversao&&!!this.auditoria?.registros.includes(r)&&(!this.ticket||r.ticket===this.ticket);
  }
  desfazer(r:RegistroResolucao): void {
    if(!this.podeDesfazer(r)||!this.auditoria)return;
    this.gravar(this.api.desfazerFrequencia(this.paciente,this.vinculo,this.auditoria.versaoVinculo,r.id,this.justificativa.trim()));
  }
  podeOrigem(acao:'aplicar'|'reabrir'|'desfazer'): boolean {
    const a=this.auditoriaOrigem;
    if(!this.motivoValido||!a||!a.integridadeEventos||a.reaberturaPosterior||a.decisao?.resultado!=='APROVAR'||!a.prova)return false;
    if(acao==='aplicar')return a.estadoAplicacao==='SEM_APLICACAO'&&!a.aplicacao;
    if(acao==='desfazer')return a.estadoAplicacao==='ORIGEM_RECONHECIDA'&&!!a.aplicacao&&!a.reversao;
    return a.estadoAplicacao==='REVERTIDA'||(a.estadoAplicacao==='SEM_APLICACAO'&&!a.aplicacao);
  }
  comandoOrigem(acao:'aplicar'|'reabrir'|'desfazer'):void {
    const a=this.auditoriaOrigem;if(!a||!this.podeOrigem(acao))return;
    this.gravar(this.api.comandoOrigem(this.paciente,this.vinculo,a.versaoVinculo,acao,
      acao==='desfazer'?a.aplicacao!.id:this.ticket,this.justificativa.trim()));
  }
  private gravar(req:Observable<{id:string}>):void {
    const g=this.geracao;this.salvando=true;this.erro='';this.aviso='';
    req.pipe(takeUntilDestroyed(this.destroy),finalize(()=>{if(g===this.geracao){this.salvando=false;this.cdr.markForCheck();}})).subscribe({
      next:()=>{if(g!==this.geracao)return;this.exigeConsulta=true;this.confirmou=false;this.justificativa='';this.aviso='Operação registrada. Atualize a consulta para verificar o estado e a trilha resultantes.';},
      error:e=>{if(g!==this.geracao)return;this.exigeConsulta=true;this.erro=interpretarErroHttp(e,{escrita:true,padra:'Não foi possível registrar a operação.'}).mensagem;}
    });
  }
}
