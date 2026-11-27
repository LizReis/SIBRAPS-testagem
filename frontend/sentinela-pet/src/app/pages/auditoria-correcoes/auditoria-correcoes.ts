import { CommonModule } from '@angular/common';
import { Component, ChangeDetectorRef, DestroyRef, OnInit, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Subscription } from 'rxjs';
import { interpretarErroHttp } from '../../services/erro-http';
import { raizClinica } from '../../services/configuracao-contrato';

interface Correcao {
  id:string; pacienteId:string; participanteId:number|null; usuarioId:string; perfil:string;
  responsavelAtendimentoId:string; tipoCorrecao:string; situacaoReserva:string|null; situacaoSessao:string|null;
  frequenciaAnterior:string; frequenciaNova:string; justificativa:string; ocorridoEm:string; versaoAnterior:number; versaoResultante:number;
}
interface Pagina { content:Correcao[];totalElements:number;totalPages:number;number:number;size:number; }

@Component({
  selector:'app-auditoria-correcoes',standalone:true,imports:[CommonModule,RouterLink],
  templateUrl:'./auditoria-correcoes.html',styleUrl:'../relatorios/relatorios.css',
})
export class AuditoriaCorrecoes implements OnInit {
  private readonly http=inject(HttpClient);
  private readonly route=inject(ActivatedRoute);
  private readonly cdr=inject(ChangeDetectorRef);
  private readonly destroy=inject(DestroyRef);
  private consulta?: Subscription;
  private geracao=0;
  tipo='';
  registro='';
  dados?:Pagina;
  carregando=false;
  erro='';
  pagina=0;
  ngOnInit():void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(p=>{
      this.tipo=this.route.snapshot.data['tipo'];
      this.registro=p.get('registro')??'';
      this.carregar(0);
    });
  }
  carregar(pagina=this.pagina):void {
    if(pagina<0)return;
    const g=++this.geracao;
    this.consulta?.unsubscribe();
    this.dados=undefined;this.erro='';this.pagina=pagina;
    if(!/^[1-9][0-9]*$/.test(this.registro)||!['individual','coletiva'].includes(this.tipo)){
      this.erro='Registro não encontrado.';this.carregando=false;return;
    }
    const recurso=this.tipo==='individual'?'agendamentos':'grupos/sessoes';
    this.carregando=true;
    this.consulta=this.http.get<Pagina>(raizClinica+'/'+recurso+'/'+this.registro+'/auditoria-correcoes',{params:{page:pagina,size:20}})
      .pipe(takeUntilDestroyed(this.destroy),finalize(()=>{if(g===this.geracao){this.carregando=false;this.cdr.markForCheck();}}))
      .subscribe({
        next:d=>{if(g===this.geracao)this.dados=d;},
        error:e=>{if(g===this.geracao)this.erro=interpretarErroHttp(e,{padrao:'Não foi possível consultar as correções autorizadas.'}).mensagem;},
      });
  }
}
