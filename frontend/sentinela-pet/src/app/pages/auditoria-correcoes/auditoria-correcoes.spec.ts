import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuditoriaCorrecoes } from './auditoria-correcoes';
import { raizClinica } from '../../services/configuracao-contrato';

describe('AuditoriaCorrecoes',()=>{
  let tipo:string;
  let http:HttpTestingController;
  beforeEach(async()=>{
    tipo='individual';
    await TestBed.configureTestingModule({imports:[AuditoriaCorrecoes],providers:[provideRouter([]),provideHttpClient(),provideHttpClientTesting(),
      {provide:ActivatedRoute,useValue:{get snapshot(){return {data:{tipo}};},paramMap:of(convertToParamMap({registro:'9'}))}}]}).compileComponents();
    http=TestBed.inject(HttpTestingController);
  });
  afterEach(()=>http.verify());
  it('consulta correções individuais paginadas e limpa conteúdo após falha',()=>{
    const f=TestBed.createComponent(AuditoriaCorrecoes);f.detectChanges();
    const url=raizClinica+'/agendamentos/9/auditoria-correcoes';
    http.expectOne({method:'GET',url:url+'?page=0&size=20'}).flush({content:[],totalElements:0,totalPages:0,number:0,size:20});
    expect(f.componentInstance.dados?.totalElements).toBe(0);
    f.componentInstance.carregar(1);
    http.expectOne({method:'GET',url:url+'?page=1&size=20'}).flush({code:'RESOURCE_NOT_FOUND'},{status:404,statusText:'Not Found'});
    expect(f.componentInstance.dados).toBeUndefined();
    expect(f.componentInstance.erro).toBeTruthy();
  });
  it('consulta a sessão coletiva sem comando de escrita',()=>{
    tipo='coletiva';const f=TestBed.createComponent(AuditoriaCorrecoes);f.detectChanges();
    http.expectOne({method:'GET',url:raizClinica+'/grupos/sessoes/9/auditoria-correcoes?page=0&size=20'}).flush({content:[],totalElements:0,totalPages:0,number:0,size:20});
    expect(f.componentInstance.tipo).toBe('coletiva');
  });
});
