import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { Sidebar } from './sidebar';

describe('Sidebar', () => {
  let tipoUsuario: string;
  beforeEach(async () => {
    tipoUsuario = 'ADMINISTRADOR';
    await TestBed.configureTestingModule({
      imports: [Sidebar],
      providers: [
        provideRouter([]),
        { provide: UsuarioLogadoService, useValue: { obterUsuarioLogado: () => of({ ...{ idPublico: 'admin-teste', nome: 'Admin Teste', email: 'admin@test.local', tipoUsuario: 'ADMINISTRADOR', unidadeAtuacao: 'CAPS_I' }, tipoUsuario }) } },
      ],
    }).compileComponents();
  });

  it('should create e mostrar profissionais para administrador', () => {
    const fixture = TestBed.createComponent(Sidebar);
    fixture.detectChanges();
    expect(fixture.componentInstance).toBeTruthy();
    expect(fixture.nativeElement.querySelector('a[href="/profissionais"]')).not.toBeNull();
  });

  it('não mostra profissionais para o perfil profissional', () => {
    tipoUsuario = 'PROFISSIONAL';
    const fixture = TestBed.createComponent(Sidebar);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a[href="/profissionais"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('a[href="/pacientes"]')).not.toBeNull();
  });
});
