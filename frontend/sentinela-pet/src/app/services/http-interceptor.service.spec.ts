import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { interpretarErroHttp } from './erro-http';
import { meuhttpInterceptor } from './http-interceptor.service';
import { LoginService } from './login/login-service';
import { UsuarioLogadoService } from './usuario-logado-service';

describe('Interceptor HTTP da sessão', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let sessao: LoginService;
  const navigate = vi.fn().mockResolvedValue(true);

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(withInterceptors([meuhttpInterceptor])),
      provideHttpClientTesting(),
      { provide: Router, useValue: { url: '/agenda', navigate } },
    ] });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    sessao = TestBed.inject(LoginService);
    sessao.addToken('jwt-sintetico');
    navigate.mockClear();
  });

  afterEach(() => {
    http.verify();
    sessao.removerToken();
    vi.restoreAllMocks();
  });

  it.each([{}, { code: 'AUTHENTICATION_REQUIRED' }])('limpa a sessão inválida e redireciona', (body) => {
    const falha = vi.fn();
    client.get('/api/usuarios/me').subscribe({ error: falha });
    const req = http.expectOne('/api/usuarios/me');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt-sintetico');
    req.flush(body, { status: 401, statusText: 'Unauthorized' });
    expect(falha).toHaveBeenCalledOnce();
    expect(sessao.getToken()).toBeNull();
    expect(navigate).toHaveBeenCalledExactlyOnceWith(['/login']);
  });

  it('propaga 403 sem derrubar a sessão e sem registrar o corpo', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const falha = vi.fn();
    client.get('/api/pacientes').subscribe({ error: falha });
    http.expectOne('/api/pacientes').flush({ code: 'ACCESS_DENIED', message: 'Bearer segredo' }, {
      status: 403, statusText: 'Forbidden',
    });
    expect(interpretarErroHttp(falha.mock.calls[0][0]).tipo).toBe('ACCESS_DENIED');
    expect(sessao.getToken()).toBe('jwt-sintetico');
    expect(navigate).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('descarta o usuário em cache após 401 e autenticação de outra conta', async () => {
    const usuarios = TestBed.inject(UsuarioLogadoService);
    const anterior = firstValueFrom(usuarios.obterUsuarioLogado());
    http.expectOne('/api/usuarios/me').flush({ idPublico: 'conta-anterior' });
    expect((await anterior).idPublico).toBe('conta-anterior');
    expect((await firstValueFrom(usuarios.obterUsuarioLogado())).idPublico).toBe('conta-anterior');
    http.expectNone('/api/usuarios/me');

    client.get('/api/pacientes').subscribe({ error: () => undefined });
    http.expectOne('/api/pacientes').flush({}, { status: 401, statusText: 'Unauthorized' });
    sessao.addToken('sessao-nova-sintetica');

    const atual = firstValueFrom(usuarios.obterUsuarioLogado());
    const req = http.expectOne('/api/usuarios/me');
    expect(req.request.headers.get('Authorization')).toBe('Bearer sessao-nova-sintetica');
    req.flush({ idPublico: 'conta-atual' });
    expect((await atual).idPublico).toBe('conta-atual');
  });

  it('preserva autenticação nova quando uma resposta atrasada recusa o token anterior', () => {
    client.get('/api/usuarios/me').subscribe({ error: () => undefined });
    sessao.addToken('sessao-nova-sintetica');
    http.expectOne('/api/usuarios/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(sessao.getToken()).toBe('sessao-nova-sintetica');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('deixa a tela de login apresentar credenciais inválidas sem nova navegação', () => {
    const falha = vi.fn();
    client.post('/api/auth/login', {}).subscribe({ error: falha });
    const req = http.expectOne('/api/auth/login');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({ code: 'AUTHENTICATION_REQUIRED' }, { status: 401, statusText: 'Unauthorized' });
    expect(falha).toHaveBeenCalledOnce();
    expect(navigate).not.toHaveBeenCalled();
  });

  it.each([0, 500, 503])('propaga HTTP %i em escrita sem retentativa', (status) => {
    const falha = vi.fn();
    const caminho = '/api/agendamentos';
    client.post(caminho, {}).subscribe({ error: falha });
    const req = http.expectOne({ url: caminho, method: 'POST' });
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      req.flush({}, { status, statusText: 'Falha' });
    }
    http.expectNone(caminho);
    expect(falha).toHaveBeenCalledOnce();
    expect(interpretarErroHttp(falha.mock.calls[0][0], { escrita: true }).mensagem).toContain('Confira o estado atual');
  });

  it('lê erro JSON em download Blob sem perder a correlação', async () => {
    const id = 'dd0c35c4-6a1f-4f14-9c99-6cc209788722';
    const falha = firstValueFrom(client.get('/api/relatorio', { responseType: 'blob' }))
      .catch(erro => interpretarErroHttp(erro));
    const json = JSON.stringify({ code: 'REPORT_GENERATION_FAILED', correlationId: id });
    class JsonBlob extends Blob {
      override text(): Promise<string> { return Promise.resolve(json); }
    }
    http.expectOne('/api/relatorio').flush(new JsonBlob([json], { type: 'application/json' }), {
      status: 500, statusText: 'Internal Server Error',
    });
    await expect(falha).resolves.toMatchObject({ tipo: 'REPORT_GENERATION_FAILED', correlationId: id });
  });
});
