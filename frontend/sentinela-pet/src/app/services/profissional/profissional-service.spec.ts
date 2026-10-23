import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ProfissionalService } from './profissional-service';

describe('ProfissionalService', () => {
  let service: ProfissionalService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ProfissionalService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('preserva a URL e recebe os dois perfis assistenciais', () => {
    const usuarios = [
      { idPublico: 'admin', nome: 'Admin', tipoUsuario: 'ADMINISTRADOR' },
      { idPublico: 'prof', nome: 'Profissional', tipoUsuario: 'PROFISSIONAL' },
    ];
    service.listarParaSelecao().subscribe(lista => expect(lista).toEqual(usuarios));
    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne('/api/usuarios/profissionais-selecao');
    expect(request.request.method).toBe('GET');
    request.flush(usuarios);
    http.verify();
  });
});
