import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';

import { CadastroUnicoService } from '../../services/paciente/cadastro-unico-service';
import { CadastroExistente } from './cadastro-existente';

describe('CadastroExistente', () => {
  let fixture: ComponentFixture<CadastroExistente>;
  let component: CadastroExistente;
  let conferencia: ReturnType<typeof vi.fn>;
  let vinculacao: ReturnType<typeof vi.fn>;

  const dados = {
    nome: 'Paciente Sintetico',
    nomeMae: 'Responsavel Sintetica',
    dataNascimento: '1995-02-03',
  };

  beforeEach(async () => {
    conferencia = vi.fn(() => of(dados));
    vinculacao = vi.fn(() => of(void 0));
    await TestBed.configureTestingModule({
      imports: [CadastroExistente],
      providers: [{
        provide: CadastroUnicoService,
        useValue: {
          conferirDocumentos: (...args: any[]) => conferencia(...args),
          confirmarVinculo: (...args: any[]) => vinculacao(...args),
        },
      }],
    }).compileComponents();
    fixture = TestBed.createComponent(CadastroExistente);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  function consultar(cpf = '123.123.123-12', cns = ''): void {
    component.documentosForm.setValue({ cpf, cns });
    component.conferir();
    fixture.detectChanges();
  }

  it('nao permite consultar sem documentos validos', () => {
    component.conferir();
    expect(conferencia).not.toHaveBeenCalled();
    expect(component.erro).toContain('11 dígitos');
    component.documentosForm.controls.cpf.setValue('123');
    component.conferir();
    expect(conferencia).not.toHaveBeenCalled();
  });

  it('confere somente documentos pelo POST e exibe identidade minima', () => {
    consultar();
    expect(conferencia).toHaveBeenCalledWith({ cpf: '12312312312' });
    const texto = fixture.nativeElement.textContent as string;
    expect(texto).toContain('Paciente Sintetico');
    expect(texto).toContain('Responsavel Sintetica');
    expect(texto).not.toContain('CAPS_II');
    expect(vinculacao).not.toHaveBeenCalled();
  });

  it('exige consentimento explicito e so entao confirma o vinculo', () => {
    consultar('12312312312');
    const sucesso = vi.fn();
    component.vinculoConfirmado.subscribe(sucesso);
    component.confirmarVinculoLocal();
    expect(vinculacao).not.toHaveBeenCalled();
    const checkbox = fixture.nativeElement.querySelector(
      '.confirmacao input[type="checkbox"]',
    ) as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    component.confirmarVinculoLocal();
    expect(vinculacao).toHaveBeenCalledOnce();
    expect(vinculacao).toHaveBeenCalledWith({
      cpf: '12312312312',
      nome: dados.nome,
      nomeMae: dados.nomeMae,
      dataNascimento: dados.dataNascimento,
    });
    expect(sucesso).toHaveBeenCalledOnce();
    expect(component.concluido).toBe(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Vínculo com a sua unidade confirmado');
  });

  it('alterar CPF depois da conferencia invalida o resultado e bloqueia confirmacao', () => {
    consultar();
    component.confirmouIdentidade = true;
    component.documentosForm.controls.cpf.setValue('99999999999');
    fixture.detectChanges();
    expect(component.identidade).toBeNull();
    expect(component.confirmouIdentidade).toBe(false);
    component.confirmarVinculoLocal();
    expect(vinculacao).not.toHaveBeenCalled();
  });

  it('resultado atrasado nao autoriza confirmar documentos que mudaram', () => {
    const atraso = new Subject<typeof dados>();
    conferencia.mockReturnValueOnce(atraso.asObservable());
    component.documentosForm.controls.cpf.setValue('12312312312');
    component.conferir();
    component.documentosForm.controls.cpf.setValue('99999999999');
    atraso.next(dados);
    fixture.detectChanges();
    expect(component.identidade).toBeNull();
    expect(vinculacao).not.toHaveBeenCalled();
  });

  it('divergencia do servidor cancela confirmacao e nao emite sucesso', () => {
    vinculacao.mockReturnValueOnce(throwError(() => ({ status: 422 })));
    consultar();
    const sucesso = vi.fn();
    component.vinculoConfirmado.subscribe(sucesso);
    component.confirmouIdentidade = true;
    component.confirmarVinculoLocal();
    expect(sucesso).not.toHaveBeenCalled();
    expect(component.concluido).toBe(false);
    expect(component.identidade).toBeNull();
    expect(component.erro).toContain('Vínculo não realizado');
  });

  it('404 na busca permite seguir para cadastro de paciente novo sem vincular', () => {
    conferencia.mockReturnValueOnce(throwError(() => ({ status: 404 })));
    consultar();
    expect(component.erro).toContain('Cadastro não localizado');
    expect(component.identidade).toBeNull();
    expect(vinculacao).not.toHaveBeenCalled();
  });
});
